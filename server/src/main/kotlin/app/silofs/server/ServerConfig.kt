package app.silofs.server

import app.silofs.auth.CredentialProvider
import app.silofs.blob.BlobStore
import app.silofs.blob.FsBlobStore
import app.silofs.blob.ObjectEncryption
import app.silofs.metadata.Database
import app.silofs.metadata.JdbcMetadataRepository
import java.nio.file.Path
import java.util.Base64

/**
 * Top-level server config. Built once from env vars (or defaults) and passed
 * to the Ktor module.
 */
data class ServerConfig(
    val bindHost: String,
    val bindPort: Int,
    val region: String,
    val dataDir: Path,
    val database: Database,
    val blobStore: BlobStore,
    val repository: JdbcMetadataRepository,
    val credentialProvider: CredentialProvider,
    val recoveryConfig: RecoveryConfig,
    val operationalConfig: OperationalConfig = OperationalConfig.fromEnv(),
    val operationalState: OperationalState = OperationalState(operationalConfig),
    val requestMetrics: RequestMetricsRegistry = RequestMetricsRegistry(),
    val securityConfig: SecurityConfig = SecurityConfig.fromEnv(),
    val objectEncryptionConfig: ObjectEncryptionConfig = ObjectEncryptionConfig.fromEnv(),
    val lifecycleConfig: LifecycleConfig = LifecycleConfig.fromEnv(),
    val sigv4MaxClockSkewSeconds: Long = 900L,
) {
    companion object {
        fun fromEnv(): ServerConfig {
            val host = envOrDefault("S3_BIND_HOST", "0.0.0.0")
            val port = envOrDefault("S3_BIND_PORT", "8080").toInt()
            val region = envOrDefault("S3_REGION", "us-east-1")
            val dataDir = Path.of(envOrDefault("S3_DATA_DIR", "/var/lib/silofs/data"))
            val dbUrl = envOrDefault("S3_DB_URL", "jdbc:postgresql://localhost:5432/silofs")
            val dbUser = envOrDefault("S3_DB_USER", "silofs")
            val dbPass = envOrDefault("S3_DB_PASSWORD", "silofs")
            val accessKeyId = envOrDefault("S3_ACCESS_KEY_ID", "AKIAIOSFODNN7EXAMPLE")
            val secretAccessKey = envOrDefault("S3_SECRET_ACCESS_KEY", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
            val maxClockSkew = envLong("S3_SIGV4_MAX_CLOCK_SKEW_SECONDS", 900L)
            val securityConfig = SecurityConfig.fromEnv()
            val objectEncryptionConfig = ObjectEncryptionConfig.fromEnv()

            val database = Database.fromUrl(dbUrl, dbUser, dbPass)
            val repo = JdbcMetadataRepository()
            // Seed the access key row so the auth layer can look it up.
            database.withConnection { c ->
                repo.upsertAccessKeyRecord(
                    c,
                    accessKeyRecordForSecret(
                        accessKeyId = accessKeyId,
                        secret = secretAccessKey,
                        description = "default env-provided key",
                        securityConfig = securityConfig,
                    ),
                )
                repo.grantBucketPermission(c, accessKeyId, "*", "ADMIN")
            }

            val blobStore = FsBlobStore(dataDir, objectEncryptionConfig.encryption)
            val credentialProvider = DatabaseCredentialProvider(database, repo, securityConfig)

            return ServerConfig(
                bindHost = host,
                bindPort = port,
                region = region,
                dataDir = dataDir,
                database = database,
                blobStore = blobStore,
                repository = repo,
                credentialProvider = credentialProvider,
                recoveryConfig = RecoveryConfig.fromEnv(),
                operationalConfig = OperationalConfig.fromEnv(),
                securityConfig = securityConfig,
                objectEncryptionConfig = objectEncryptionConfig,
                lifecycleConfig = LifecycleConfig.fromEnv(),
                sigv4MaxClockSkewSeconds = maxClockSkew,
            )
        }

        private fun envOrDefault(
            name: String,
            default: String,
        ): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

        private fun envLong(
            name: String,
            default: Long,
        ): Long = System.getenv(name)?.toLongOrNull() ?: default
    }
}

data class LifecycleConfig(
    val enabled: Boolean,
    val sweepIntervalSeconds: Long,
    val batchSize: Int,
) {
    companion object {
        fun fromEnv(): LifecycleConfig =
            LifecycleConfig(
                enabled = envOrDefault("S3_LIFECYCLE_ENABLED", "false").toBoolean(),
                sweepIntervalSeconds = envLong("S3_LIFECYCLE_SWEEP_INTERVAL_SECONDS", 3600L),
                batchSize = envOrDefault("S3_LIFECYCLE_BATCH_SIZE", "1000").toInt(),
            )

        private fun envLong(
            name: String,
            default: Long,
        ): Long = System.getenv(name)?.toLongOrNull() ?: default

        private fun envOrDefault(
            name: String,
            default: String,
        ): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
    }
}

data class ObjectEncryptionConfig(
    val mode: String,
    val encryption: ObjectEncryption?,
    val requireObjectEncryption: Boolean,
) {
    val isEnabled: Boolean
        get() = mode == MODE_SSE_S3

    companion object {
        const val MODE_DISABLED = "disabled"
        const val MODE_SSE_S3 = "sse-s3"

        fun fromEnv(): ObjectEncryptionConfig {
            val mode = envOrDefault("S3_OBJECT_ENCRYPTION_MODE", MODE_DISABLED).lowercase()
            val require = envOrDefault("S3_REQUIRE_OBJECT_ENCRYPTION", "false").toBoolean()
            if (mode !in setOf(MODE_DISABLED, MODE_SSE_S3)) {
                throw IllegalArgumentException("S3_OBJECT_ENCRYPTION_MODE must be disabled or sse-s3")
            }
            if (require && mode != MODE_SSE_S3) {
                throw IllegalArgumentException("S3_REQUIRE_OBJECT_ENCRYPTION=true requires S3_OBJECT_ENCRYPTION_MODE=sse-s3")
            }
            val encryption =
                if (mode == MODE_SSE_S3) {
                    val raw = envOrDefault("S3_OBJECT_ENCRYPTION_MASTER_KEY", "")
                    if (raw.isBlank()) {
                        throw IllegalArgumentException("S3_OBJECT_ENCRYPTION_MASTER_KEY is required when object encryption is enabled")
                    }
                    val key = Base64.getDecoder().decode(raw)
                    ObjectEncryption(key)
                } else {
                    null
                }
            return ObjectEncryptionConfig(mode, encryption, require)
        }

        private fun envOrDefault(
            name: String,
            default: String,
        ): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
    }
}

data class RecoveryConfig(
    val tempMaxAgeSeconds: Long,
    val multipartMaxAgeSeconds: Long,
    val sweepIntervalSeconds: Long,
    val blobSweepIntervalSeconds: Long,
    val enabled: Boolean,
) {
    companion object {
        fun fromEnv(): RecoveryConfig =
            RecoveryConfig(
                tempMaxAgeSeconds = envLong("S3_RECOVERY_TMP_MAX_AGE_SECONDS", 3600L),
                multipartMaxAgeSeconds = envLong("S3_RECOVERY_MULTIPART_MAX_AGE_SECONDS", 24 * 3600L),
                sweepIntervalSeconds = envLong("S3_RECOVERY_SWEEP_INTERVAL_SECONDS", 60L),
                blobSweepIntervalSeconds = envLong("S3_RECOVERY_BLOB_SWEEP_INTERVAL_SECONDS", 600L),
                enabled = envOrDefault("S3_RECOVERY_ENABLED", "true").toBoolean(),
            )

        private fun envLong(
            name: String,
            default: Long,
        ): Long = System.getenv(name)?.toLongOrNull() ?: default

        private fun envOrDefault(
            name: String,
            default: String,
        ): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
    }
}
