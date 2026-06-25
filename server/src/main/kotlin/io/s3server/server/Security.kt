package app.silofs.server

import app.silofs.auth.AwsCredentials
import app.silofs.auth.CredentialProvider
import app.silofs.metadata.AccessKeyRecord
import app.silofs.metadata.Database
import app.silofs.metadata.JdbcMetadataRepository
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SecurityConfig(
    val secretEncryptionKey: ByteArray?,
    val requireEncryptedSecrets: Boolean,
    val corsAllowedOrigins: List<String>,
    val rateLimitPerAccessKeyRps: Int,
    val rateLimitPerAccessKeyBurst: Int,
) {
    init {
        require(secretEncryptionKey == null || secretEncryptionKey.size == 32) {
            "S3_ACCESS_KEY_SECRET_ENCRYPTION_KEY must decode to exactly 32 bytes"
        }
        require(rateLimitPerAccessKeyRps >= 0) { "S3_RATE_LIMIT_PER_ACCESS_KEY_RPS must be >= 0" }
        require(rateLimitPerAccessKeyBurst > 0) { "S3_RATE_LIMIT_PER_ACCESS_KEY_BURST must be > 0" }
        require(!requireEncryptedSecrets || secretEncryptionKey != null) {
            "S3_REQUIRE_ENCRYPTED_SECRETS=true requires S3_ACCESS_KEY_SECRET_ENCRYPTION_KEY"
        }
    }

    val secretCodec: AccessKeySecretCodec? =
        secretEncryptionKey?.let { AccessKeySecretCodec(it, keyId = "env:aes-gcm") }

    companion object {
        fun fromEnv(): SecurityConfig =
            SecurityConfig(
                secretEncryptionKey = System.getenv("S3_ACCESS_KEY_SECRET_ENCRYPTION_KEY")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Base64.getDecoder().decode(it) },
                requireEncryptedSecrets = envOrDefault("S3_REQUIRE_ENCRYPTED_SECRETS", "false").toBoolean(),
                corsAllowedOrigins = envOrDefault("S3_CORS_ALLOWED_ORIGINS", "")
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                rateLimitPerAccessKeyRps = envInt("S3_RATE_LIMIT_PER_ACCESS_KEY_RPS", 0),
                rateLimitPerAccessKeyBurst = envInt("S3_RATE_LIMIT_PER_ACCESS_KEY_BURST", 64),
            )

        private fun envOrDefault(name: String, default: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

        private fun envInt(name: String, default: Int): Int =
            System.getenv(name)?.toIntOrNull() ?: default
    }
}

class AccessKeySecretCodec(
    private val key: ByteArray,
    val keyId: String,
) {
    private val random = SecureRandom()

    fun encrypt(accessKeyId: String, secret: String): EncryptedSecret {
        val nonce = ByteArray(12)
        random.nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(accessKeyId.toByteArray(Charsets.UTF_8))
        return EncryptedSecret(cipher.doFinal(secret.toByteArray(Charsets.UTF_8)), nonce, keyId)
    }

    fun decrypt(accessKeyId: String, ciphertext: ByteArray, nonce: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(accessKeyId.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }
}

data class EncryptedSecret(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val keyId: String,
)

class DatabaseCredentialProvider(
    private val database: Database,
    private val repository: JdbcMetadataRepository,
    private val securityConfig: SecurityConfig,
) : CredentialProvider {
    override fun lookup(accessKeyId: String): AwsCredentials? =
        database.withConnection { conn ->
            repository.lookupAccessKey(conn, accessKeyId)?.let { record ->
                val secret = record.secretForAuth(securityConfig) ?: return@withConnection null
                AwsCredentials(record.accessKeyId, secret)
            }
        }
}

fun AccessKeyRecord.secretForAuth(securityConfig: SecurityConfig): String? {
    val ciphertext = secretCiphertext
    val nonce = secretNonce
    if (ciphertext != null && nonce != null && securityConfig.secretCodec != null) {
        return securityConfig.secretCodec.decrypt(accessKeyId, ciphertext, nonce)
    }
    if (securityConfig.requireEncryptedSecrets) return null
    return secretAccessKey
}

fun accessKeyRecordForSecret(
    accessKeyId: String,
    secret: String,
    description: String?,
    securityConfig: SecurityConfig,
    rotated: Boolean = false,
): AccessKeyRecord {
    val encrypted = securityConfig.secretCodec?.encrypt(accessKeyId, secret)
    return AccessKeyRecord(
        accessKeyId = accessKeyId,
        secretAccessKey = if (encrypted == null && !securityConfig.requireEncryptedSecrets) secret else null,
        secretCiphertext = encrypted?.ciphertext,
        secretNonce = encrypted?.nonce,
        secretKeyId = encrypted?.keyId,
        description = description,
        state = "ACTIVE",
        rotatedAt = if (rotated) java.time.Instant.now() else null,
    )
}

object AccessKeyGenerator {
    private val random = SecureRandom()
    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray()
    private val secretAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789/+".toCharArray()

    fun accessKeyId(): String = "AKIA" + randomString(alphabet, 16)

    fun secretAccessKey(): String = randomString(secretAlphabet, 40)

    private fun randomString(chars: CharArray, length: Int): String =
        buildString(length) {
            repeat(length) {
                append(chars[random.nextInt(chars.size)])
            }
        }
}

class AccessKeyRateLimiter(
    private val rps: Int,
    private val burst: Int,
) {
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun allow(accessKeyId: String): Boolean {
        if (rps <= 0) return true
        val bucket = buckets.computeIfAbsent(accessKeyId) { Bucket(burst.toDouble(), System.nanoTime()) }
        return bucket.tryConsume(rps, burst)
    }

    private class Bucket(
        private var tokens: Double,
        private var lastRefillNanos: Long,
    ) {
        @Synchronized
        fun tryConsume(rps: Int, burst: Int): Boolean {
            val now = System.nanoTime()
            val elapsedSeconds = (now - lastRefillNanos).coerceAtLeast(0L).toDouble() / 1_000_000_000.0
            tokens = (tokens + elapsedSeconds * rps).coerceAtMost(burst.toDouble())
            lastRefillNanos = now
            if (tokens < 1.0) return false
            tokens -= 1.0
            return true
        }
    }
}
