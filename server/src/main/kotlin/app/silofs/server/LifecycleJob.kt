package app.silofs.server

import app.silofs.metadata.Database
import app.silofs.metadata.JdbcMetadataRepository
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class LifecycleJob(
    private val database: Database,
    private val repository: JdbcMetadataRepository,
    private val config: LifecycleConfig,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(LifecycleJob::class.java)
    private var executor: ScheduledExecutorService? = null

    fun start() {
        if (!config.enabled || executor != null) return
        val next =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "silofs-lifecycle").apply { isDaemon = true }
            }
        executor = next
        next.scheduleWithFixedDelay(
            { runCatching { runOnce() }.onFailure { log.warn("Lifecycle sweep failed", it) } },
            config.sweepIntervalSeconds,
            config.sweepIntervalSeconds,
            TimeUnit.SECONDS,
        )
    }

    fun runOnce(): Int =
        database
            .withTransaction { conn ->
                repository.expireLifecycleObjects(conn, config.batchSize, Instant.now())
            }.also { expired ->
                if (expired > 0) log.info("Lifecycle expired {} object version(s)", expired)
            }

    override fun close() {
        executor?.shutdownNow()
        executor = null
    }
}
