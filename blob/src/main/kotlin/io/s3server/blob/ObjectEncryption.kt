package app.silofs.blob

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class BlobEncryptionMetadata(
    val mode: String,
    val keyId: String,
    val nonce: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BlobEncryptionMetadata) return false
        return mode == other.mode && keyId == other.keyId && nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int {
        var result = mode.hashCode()
        result = 31 * result + keyId.hashCode()
        result = 31 * result + nonce.contentHashCode()
        return result
    }
}

class ObjectEncryption(
    private val masterKey: ByteArray,
    val keyId: String = DEFAULT_KEY_ID,
    private val random: SecureRandom = SecureRandom(),
) {
    init {
        require(masterKey.size == 32) { "SSE-S3 master key must be 32 bytes" }
    }

    fun encryptFile(
        plaintextPath: Path,
        encryptedPath: Path,
        plaintextSha256Hex: String,
        plaintextSize: Long,
    ): BlobEncryptionMetadata {
        val nonce = ByteArray(NONCE_BYTES)
        random.nextBytes(nonce)
        val sha = hexToBytes(plaintextSha256Hex)
        val keyIdBytes = keyId.toByteArray(Charsets.UTF_8)
        Files.createDirectories(encryptedPath.parent)
        FileChannel.open(encryptedPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
            val header = headerBytes(nonce, keyIdBytes, plaintextSize, sha)
            out.write(ByteBuffer.wrap(header))
            val cipher = cipher(Cipher.ENCRYPT_MODE, nonce)
            cipher.updateAAD(aadBytes(nonce, keyIdBytes, plaintextSize, sha))
            CipherOutputStream(NonClosingOutputStream(Channels.newOutputStream(out)), cipher).use { cipherOut ->
                Files.newInputStream(plaintextPath).use { input -> input.copyTo(cipherOut) }
            }
            out.force(true)
        }
        return BlobEncryptionMetadata(SSE_S3_MODE, keyId, nonce)
    }

    fun openDecryptedChannel(encryptedPath: Path): FileChannel {
        val tmp = Files.createTempFile("silofs-decrypt-", ".tmp")
        try {
            Files.newInputStream(encryptedPath).use { input ->
                val header = readHeader(input)
                val cipher = cipher(Cipher.DECRYPT_MODE, header.nonce)
                cipher.updateAAD(aadBytes(header.nonce, header.keyIdBytes, header.plaintextSize, header.sha256Bytes))
                CipherInputStream(input, cipher).use { cipherIn ->
                    Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
                        cipherIn.copyTo(out)
                    }
                }
            }
            return FileChannel.open(tmp, StandardOpenOption.READ, StandardOpenOption.DELETE_ON_CLOSE)
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw t
        }
    }

    fun validate(encryptedPath: Path, expectedSha256Hex: String, expectedSize: Long) {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var size = 0L
        openDecryptedChannel(encryptedPath).use { ch ->
            val buf = ByteBuffer.allocate(64 * 1024)
            while (true) {
                buf.clear()
                val n = ch.read(buf)
                if (n <= 0) break
                size += n.toLong()
                digest.update(buf.array(), 0, n)
            }
        }
        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
        check(size == expectedSize) { "encrypted blob plaintext size mismatch: expected=$expectedSize actual=$size" }
        check(actualSha.equals(expectedSha256Hex, ignoreCase = true)) {
            "encrypted blob plaintext SHA-256 mismatch: expected=$expectedSha256Hex actual=$actualSha"
        }
    }

    private fun cipher(mode: Int, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").also {
            it.init(mode, SecretKeySpec(masterKey, "AES"), GCMParameterSpec(128, nonce))
        }

    private data class ParsedHeader(
        val nonce: ByteArray,
        val keyIdBytes: ByteArray,
        val plaintextSize: Long,
        val sha256Bytes: ByteArray,
    )

    companion object {
        const val SSE_S3_MODE = "SSE-S3"
        const val DEFAULT_KEY_ID = "local-v1"
        private val MAGIC = "SILOFSENC1".toByteArray(Charsets.US_ASCII)
        private const val VERSION: Byte = 1
        private const val NONCE_BYTES = 12
        private const val SHA256_BYTES = 32

        fun isEncryptedBlob(path: Path): Boolean {
            if (!Files.exists(path) || Files.size(path) < MAGIC.size + 1L) return false
            FileChannel.open(path, StandardOpenOption.READ).use { ch ->
                val buf = ByteBuffer.allocate(MAGIC.size)
                if (ch.read(buf) != MAGIC.size) return false
                return buf.array().contentEquals(MAGIC)
            }
        }

        private fun headerBytes(
            nonce: ByteArray,
            keyIdBytes: ByteArray,
            plaintextSize: Long,
            sha256Bytes: ByteArray,
        ): ByteArray {
            require(nonce.size == NONCE_BYTES)
            require(sha256Bytes.size == SHA256_BYTES)
            require(keyIdBytes.size <= UShort.MAX_VALUE.toInt())
            return ByteBuffer.allocate(MAGIC.size + 1 + NONCE_BYTES + 2 + keyIdBytes.size + 8 + SHA256_BYTES)
                .put(MAGIC)
                .put(VERSION)
                .put(nonce)
                .putShort(keyIdBytes.size.toShort())
                .put(keyIdBytes)
                .putLong(plaintextSize)
                .put(sha256Bytes)
                .array()
        }

        private fun aadBytes(
            nonce: ByteArray,
            keyIdBytes: ByteArray,
            plaintextSize: Long,
            sha256Bytes: ByteArray,
        ): ByteArray = headerBytes(nonce, keyIdBytes, plaintextSize, sha256Bytes)

        private fun readHeader(input: java.io.InputStream): ParsedHeader {
            val magic = input.readNBytes(MAGIC.size)
            require(magic.contentEquals(MAGIC)) { "not a silofs encrypted blob" }
            val version = input.read()
            require(version == VERSION.toInt()) { "unsupported silofs encrypted blob version: $version" }
            val nonce = input.readNBytes(NONCE_BYTES)
            require(nonce.size == NONCE_BYTES) { "truncated encrypted blob nonce" }
            val keyLenBytes = input.readNBytes(2)
            require(keyLenBytes.size == 2) { "truncated encrypted blob key id length" }
            val keyLen = ByteBuffer.wrap(keyLenBytes).short.toInt() and 0xffff
            val keyIdBytes = input.readNBytes(keyLen)
            require(keyIdBytes.size == keyLen) { "truncated encrypted blob key id" }
            val sizeBytes = input.readNBytes(8)
            require(sizeBytes.size == 8) { "truncated encrypted blob size" }
            val plaintextSize = ByteBuffer.wrap(sizeBytes).long
            val sha = input.readNBytes(SHA256_BYTES)
            require(sha.size == SHA256_BYTES) { "truncated encrypted blob sha256" }
            return ParsedHeader(nonce, keyIdBytes, plaintextSize, sha)
        }

        private fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0) { "hex string must have even length" }
            val out = ByteArray(hex.length / 2)
            var i = 0
            while (i < hex.length) {
                out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
                i += 2
            }
            return out
        }
    }
}

private class NonClosingOutputStream(private val delegate: OutputStream) : OutputStream() {
    override fun write(b: Int) = delegate.write(b)

    override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)

    override fun flush() = delegate.flush()

    override fun close() = delegate.flush()
}
