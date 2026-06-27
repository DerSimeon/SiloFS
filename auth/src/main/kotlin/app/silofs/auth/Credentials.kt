package app.silofs.auth

/**
 * AWS credentials. In M1 we load a single static pair from env vars; the
 * [CredentialProvider] interface lets us swap in a Postgres-backed store later.
 */
data class AwsCredentials(
    val accessKeyId: String,
    val secretAccessKey: String,
)

interface CredentialProvider {
    fun lookup(accessKeyId: String): AwsCredentials?
}

/** Simple in-memory provider — used by tests and as the M1 default. */
class StaticCredentialProvider(
    private val creds: Map<String, AwsCredentials>,
) : CredentialProvider {
    override fun lookup(accessKeyId: String): AwsCredentials? = creds[accessKeyId]

    companion object {
        fun single(
            accessKeyId: String,
            secretAccessKey: String,
        ): StaticCredentialProvider = StaticCredentialProvider(mapOf(accessKeyId to AwsCredentials(accessKeyId, secretAccessKey)))
    }
}
