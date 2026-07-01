package app.silofs.test

import app.silofs.metadata.JdbcMetadataRepository
import app.silofs.server.accessKeyRecordForSecret
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.VersioningConfiguration
import java.net.URI
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ServerGovernanceTest : AbstractS3ServerTest() {
    private fun newBucket(): String =
        "gov-" +
            UUID
                .randomUUID()
                .toString()
                .take(8)
                .lowercase()

    @Test
    fun `bucket grants restrict reads and writes`() {
        val bucket = newBucket()
        newS3Client().use { admin ->
            admin.createBucket { it.bucket(bucket) }
            admin.putObject(
                { it.bucket(bucket).key("allowed.txt") },
                RequestBody.fromBytes("ok".toByteArray()),
            )
        }

        val readOnlyKey = "AKIAREADONLY00000001"
        val readOnlySecret = "readonly-secret"
        database.withConnection { conn ->
            val repo = JdbcMetadataRepository()
            repo.upsertAccessKeyRecord(conn, accessKeyRecordForSecret(readOnlyKey, readOnlySecret, "read only", securityConfig))
            repo.grantBucketPermission(conn, readOnlyKey, bucket, "READ")
        }

        client(readOnlyKey, readOnlySecret).use { readOnly ->
            val got = readOnly.getObjectAsBytes { it.bucket(bucket).key("allowed.txt") }
            assertArrayEquals("ok".toByteArray(), got.asByteArray())
            assertThrows<S3Exception> {
                readOnly.putObject(
                    { it.bucket(bucket).key("denied.txt") },
                    RequestBody.fromBytes("nope".toByteArray()),
                )
            }.also { assertEquals(403, it.statusCode()) }
        }
    }

    @Test
    fun `versioning creates versions and delete markers`() {
        val bucket = newBucket()
        newS3Client().use { s3 ->
            s3.createBucket { it.bucket(bucket) }
            s3.putBucketVersioning {
                it
                    .bucket(bucket)
                    .versioningConfiguration(
                        VersioningConfiguration
                            .builder()
                            .status(BucketVersioningStatus.ENABLED)
                            .build(),
                    )
            }

            val v1 =
                s3
                    .putObject(
                        { it.bucket(bucket).key("doc.txt") },
                        RequestBody.fromBytes("one".toByteArray()),
                    ).versionId()
            val v2 =
                s3
                    .putObject(
                        { it.bucket(bucket).key("doc.txt") },
                        RequestBody.fromBytes("two".toByteArray()),
                    ).versionId()
            assertNotNull(v1)
            assertNotNull(v2)
            assertTrue(v1 != v2)

            assertArrayEquals("two".toByteArray(), s3.getObjectAsBytes { it.bucket(bucket).key("doc.txt") }.asByteArray())
            assertArrayEquals(
                "one".toByteArray(),
                s3.getObjectAsBytes { it.bucket(bucket).key("doc.txt").versionId(v1) }.asByteArray(),
            )

            val deleted = s3.deleteObject { it.bucket(bucket).key("doc.txt") }
            assertTrue(deleted.deleteMarker())
            assertNotNull(deleted.versionId())
            assertThrows<S3Exception> { s3.headObject { it.bucket(bucket).key("doc.txt") } }

            val versions = s3.listObjectVersions { it.bucket(bucket).prefix("doc") }
            assertEquals(2, versions.versions().size)
            assertEquals(1, versions.deleteMarkers().size)
        }
    }

    private fun client(
        accessKey: String,
        secret: String,
    ): S3Client =
        S3Client
            .builder()
            .region(Region.of(REGION))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secret)))
            .endpointOverride(URI.create(endpoint))
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .serviceConfiguration(
                S3Configuration
                    .builder()
                    .pathStyleAccessEnabled(true)
                    .chunkedEncodingEnabled(false)
                    .build(),
            ).httpClient(ApacheHttpClient.create())
            .build()
}
