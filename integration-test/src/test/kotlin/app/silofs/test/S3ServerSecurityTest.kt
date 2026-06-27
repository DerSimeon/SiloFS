package app.silofs.test

import app.silofs.metadata.AccessKeyRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration

class S3ServerSecurityTest : AbstractS3ServerTest() {
    @Test
    fun `disabled access key fails authentication without restart`() {
        val accessKey = "AKIATESTDISABLED001"
        val secret = "disabledSecretForIntegration000000000000"
        database.withConnection { conn ->
            Jdbc.repo.upsertAccessKeyRecord(conn, AccessKeyRecord(accessKey, secret, description = "disabled test"))
        }
        newClient(accessKey, secret).use { it.listBuckets() }

        database.withConnection { conn ->
            Jdbc.repo.updateAccessKeyState(conn, accessKey, "DISABLED")
        }

        newClient(accessKey, secret).use { client ->
            val ex =
                assertThrows<software.amazon.awssdk.services.s3.model.S3Exception> {
                    client.listBuckets()
                }
            assertEquals(403, ex.statusCode())
        }
    }

    @Test
    fun `rotated access key takes effect without restart`() {
        val accessKey = "AKIATESTROTATED0001"
        val oldSecret = "oldSecretForIntegration0000000000000000"
        val newSecret = "newSecretForIntegration0000000000000000"
        database.withConnection { conn ->
            Jdbc.repo.upsertAccessKeyRecord(conn, AccessKeyRecord(accessKey, oldSecret, description = "rotate test"))
        }
        newClient(accessKey, oldSecret).use { it.listBuckets() }

        database.withConnection { conn ->
            Jdbc.repo.upsertAccessKeyRecord(conn, AccessKeyRecord(accessKey, newSecret, description = "rotate test"))
        }

        newClient(accessKey, oldSecret).use { client ->
            val ex =
                assertThrows<software.amazon.awssdk.services.s3.model.S3Exception> {
                    client.listBuckets()
                }
            assertEquals(403, ex.statusCode())
        }
        newClient(accessKey, newSecret).use { it.listBuckets() }
    }

    private fun newClient(
        accessKey: String,
        secret: String,
    ): S3Client =
        S3Client
            .builder()
            .region(Region.of(REGION))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secret)))
            .endpointOverride(java.net.URI.create(endpoint))
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .serviceConfiguration(
                S3Configuration
                    .builder()
                    .pathStyleAccessEnabled(true)
                    .chunkedEncodingEnabled(false)
                    .build(),
            ).httpClient(ApacheHttpClient.builder().build())
            .build()

    private object Jdbc {
        val repo = app.silofs.metadata.JdbcMetadataRepository()
    }
}
