package app.silofs.blob

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
