package app.silofs.test

import app.silofs.blob.FsBlobStore
import app.silofs.blob.ObjectEncryption
import app.silofs.blob.writeFromBytes
import app.silofs.common.ObjectMetadata
import app.silofs.metadata.JdbcMetadataRepository
import app.silofs.server.ObjectEncryptionConfig
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.ServerSideEncryption
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import java.util.UUID

class S3ServerEncryptionTest : AbstractS3ServerTest() {
    private val objectKey = ByteArray(32) { 42 }

    override fun objectEncryptionConfig(): ObjectEncryptionConfig =
        ObjectEncryptionConfig(
            mode = ObjectEncryptionConfig.MODE_SSE_S3,
            encryption = ObjectEncryption(objectKey),
            requireObjectEncryption = true,
        )

    @Test
    fun `SSE-S3 put head get and range use plaintext semantics with encrypted blob on disk`() {
        val bucket = "enc-${UUID.randomUUID().toString().take(8)}"
        val key = "secret.txt"
        val payload = "very secret payload".toByteArray()
        newS3Client().use { s3 ->
            s3.createBucket { it.bucket(bucket) }
            val put =
                s3.putObject(
                    PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .serverSideEncryption(ServerSideEncryption.AES256)
                        .build(),
                    RequestBody.fromBytes(payload),
                )
            assertEquals(ServerSideEncryption.AES256, put.serverSideEncryption())

            val head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
            assertEquals(ServerSideEncryption.AES256, head.serverSideEncryption())
            assertEquals(payload.size.toLong(), head.contentLength())

            val body = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray()
            assertArrayEquals(payload, body)
            val range =
                s3.getObjectAsBytes(
                    GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .range("bytes=5-10")
                        .build(),
                ).asByteArray()
            assertArrayEquals(payload.copyOfRange(5, 11), range)
        }

        val row = objectRow(bucket, key)
        assertEquals(ObjectEncryption.SSE_S3_MODE, row.encryptionMode)
        val blobPath = java.nio.file.Path.of(row.blobPath)
        assertTrue(ObjectEncryption.isEncryptedBlob(blobPath))
        assertFalse(Files.readAllBytes(blobPath).contentEquals(payload))
    }

    @Test
    fun `encrypted multipart upload completes to encrypted object`() {
        val bucket = "enc-mpu-${UUID.randomUUID().toString().take(8)}"
        val key = "large.bin"
        val partSize = 5 * 1024 * 1024
        val part1 = ByteArray(partSize) { 1 }
        val part2 = ByteArray(1024) { 2 }
        newS3Client().use { s3 ->
            s3.createBucket { it.bucket(bucket) }
            val init =
                s3.createMultipartUpload(
                    CreateMultipartUploadRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .serverSideEncryption(ServerSideEncryption.AES256)
                        .build(),
                )
            val e1 =
                s3.uploadPart(
                    UploadPartRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .uploadId(init.uploadId())
                        .partNumber(1)
                        .contentLength(part1.size.toLong())
                        .build(),
                    RequestBody.fromBytes(part1),
                ).eTag()
            val e2 =
                s3.uploadPart(
                    UploadPartRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .uploadId(init.uploadId())
                        .partNumber(2)
                        .contentLength(part2.size.toLong())
                        .build(),
                    RequestBody.fromBytes(part2),
                ).eTag()
            s3.completeMultipartUpload {
                it.bucket(bucket)
                    .key(key)
                    .uploadId(init.uploadId())
                    .multipartUpload(
                        CompletedMultipartUpload.builder()
                            .parts(
                                CompletedPart.builder().partNumber(1).eTag(e1).build(),
                                CompletedPart.builder().partNumber(2).eTag(e2).build(),
                            )
                            .build(),
                    )
            }

            val head = s3.headObject { it.bucket(bucket).key(key) }
            assertEquals(ServerSideEncryption.AES256, head.serverSideEncryption())
            assertEquals(part1.size + part2.size.toLong(), head.contentLength())
        }
        val row = objectRow(bucket, key)
        assertEquals(ObjectEncryption.SSE_S3_MODE, row.encryptionMode)
        assertTrue(ObjectEncryption.isEncryptedBlob(java.nio.file.Path.of(row.blobPath)))
    }

    @Test
    fun `legacy plaintext object remains readable when encryption is enabled`() {
        val bucket = "enc-legacy-${UUID.randomUUID().toString().take(8)}"
        val key = "legacy.txt"
        val payload = "legacy plaintext".toByteArray()
        newS3Client().use { s3 -> s3.createBucket { it.bucket(bucket) } }
        val plaintextStore = FsBlobStore(dataDir)
        val stored = plaintextStore.writeFromBytes(payload)
        val repo = JdbcMetadataRepository()
        database.withTransaction { conn ->
            repo.putObject(
                conn,
                ObjectMetadata(
                    bucket = bucket,
                    key = key,
                    blobPath = stored.blobPath.toString(),
                    blobSha256Hex = stored.sha256Hex,
                    etag = "\"legacy\"",
                    sizeBytes = stored.sizeBytes,
                    contentType = "text/plain",
                    contentEncoding = null,
                    contentLanguage = null,
                    cacheControl = null,
                    contentDisposition = null,
                    expires = null,
                    userMetadata = emptyMap(),
                    versionId = "null",
                    storageClass = "STANDARD",
                    createdAt = Instant.now(),
                ),
            )
        }

        newS3Client().use { s3 ->
            val body = s3.getObjectAsBytes { it.bucket(bucket).key(key) }.asByteArray()
            val head = s3.headObject { it.bucket(bucket).key(key) }
            assertArrayEquals(payload, body)
            assertEquals(null, head.serverSideEncryption())
        }
    }

    @Test
    fun `unsupported SSE modes return S3 errors`() {
        val bucket = "enc-bad-${UUID.randomUUID().toString().take(8)}"
        newS3Client().use { s3 ->
            s3.createBucket { it.bucket(bucket) }
            val kms =
                assertThrows<software.amazon.awssdk.services.s3.model.S3Exception> {
                    s3.putObject(
                        PutObjectRequest.builder()
                            .bucket(bucket)
                            .key("kms")
                            .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                            .ssekmsKeyId("key")
                            .build(),
                        RequestBody.fromString("x"),
                    )
                }
            assertEquals(501, kms.statusCode())

            val ssec =
                assertThrows<software.amazon.awssdk.services.s3.model.S3Exception> {
                    s3.putObject(
                        PutObjectRequest.builder()
                            .bucket(bucket)
                            .key("ssec")
                            .sseCustomerAlgorithm("AES256")
                            .sseCustomerKey(Base64.getEncoder().encodeToString(ByteArray(32) { 1 }))
                            .build(),
                        RequestBody.fromString("x"),
                    )
                }
            assertEquals(501, ssec.statusCode())
        }
    }

    private data class ObjectRow(val blobPath: String, val encryptionMode: String?)

    private fun objectRow(bucket: String, key: String): ObjectRow =
        database.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT blob_path, encryption_mode
                FROM objects
                WHERE bucket = ? AND object_key = ? AND deleted_at IS NULL
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, bucket)
                ps.setString(2, key)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    ObjectRow(rs.getString("blob_path"), rs.getString("encryption_mode"))
                }
            }
        }
}
