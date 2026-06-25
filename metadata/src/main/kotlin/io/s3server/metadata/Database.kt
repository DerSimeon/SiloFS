package app.silofs.metadata

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import app.silofs.common.S3Exception
import app.silofs.common.S3Errors
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * Wraps a HikariCP pool. Created once at server startup.
 *
 * Migrations run synchronously on [init] — the server refuses to start if
 * Flyway cannot apply them, which is the correct behaviour for a stateful service.
 */
class Database(
    val dataSource: DataSource,
    val schema: String = "public"
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(Database::class.java)

    init {
        require(dataSource is HikariDataSource) { "DataSource must be a HikariDataSource" }
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .schemas(schema)
            .baselineOnMigrate(true)
            .load()
        flyway.migrate()
        log.info("Flyway migrations applied (schema={})", schema)
    }

    /**
     * Runs [block] inside a JDBC transaction. Autocommit is disabled for the
     * duration; on any throwable the transaction is rolled back.
     */
    fun <T> withTransaction(block: (java.sql.Connection) -> T): T {
        val conn = dataSource.connection
        val previousAutoCommit = conn.autoCommit
        try {
            conn.autoCommit = false
            val result = block(conn)
            conn.commit()
            return result
        } catch (t: Throwable) {
            runCatching { conn.rollback() }
            throw t.toS3()
        } finally {
            runCatching { conn.autoCommit = previousAutoCommit }
            runCatching { conn.close() }
        }
    }

    /** Runs [block] without an explicit transaction (autocommit on). */
    fun <T> withConnection(block: (java.sql.Connection) -> T): T {
        val conn = dataSource.connection
        return try {
            block(conn)
        } finally {
            runCatching { conn.close() }
        }
    }

    fun poolStats(): DbPoolStats? {
        val hikari = dataSource as? HikariDataSource ?: return null
        val bean = hikari.hikariPoolMXBean ?: return null
        return DbPoolStats(
            activeConnections = bean.activeConnections,
            idleConnections = bean.idleConnections,
            totalConnections = bean.totalConnections,
            threadsAwaitingConnection = bean.threadsAwaitingConnection,
        )
    }

    override fun close() {
        if (dataSource is HikariDataSource) {
            dataSource.close()
        }
    }

    companion object {
        fun fromUrl(url: String, username: String, password: String, schema: String = "public"): Database {
            val cfg = HikariConfig().apply {
                jdbcUrl = url
                this.username = username
                this.password = password
                driverClassName = "org.postgresql.Driver"
                poolName = "silofs-pool"
                maximumPoolSize = 16
                minimumIdle = 2
                connectionTimeout = 5_000
                idleTimeout = 60_000
                maxLifetime = 30 * 60_000
                leakDetectionThreshold = 60_000
                validate()
            }
            val ds = HikariDataSource(cfg)
            return Database(ds, schema)
        }
    }
}

data class DbPoolStats(
    val activeConnections: Int,
    val idleConnections: Int,
    val totalConnections: Int,
    val threadsAwaitingConnection: Int,
)

/**
 * Wraps a checked SQL exception in [S3Exception] so the HTTP layer can render it
 * as a 500 without leaking driver-specific stack traces to clients.
 */
fun Throwable.toS3(): S3Exception = when (this) {
    is S3Exception -> this
    else -> S3Errors.internalError(this)
}

/** Run [block]; if any throwable escapes, wrap it and rethrow as S3Exception. */
inline fun <T> withS3(block: () -> T): T = try {
    block()
} catch (e: S3Exception) {
    throw e
} catch (e: Throwable) {
    throw S3Errors.internalError(e)
}
