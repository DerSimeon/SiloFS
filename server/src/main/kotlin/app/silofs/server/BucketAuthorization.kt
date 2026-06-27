package app.silofs.server

import app.silofs.auth.AuthenticatedAccessKeyIdKey
import app.silofs.common.S3ErrorCode
import app.silofs.common.S3Exception
import io.ktor.server.application.ApplicationCall

class BucketAuthorization(
    private val config: ServerConfig,
) {
    fun require(
        call: ApplicationCall,
        bucket: String,
        permission: BucketPermission,
    ) {
        val accessKeyId =
            call.attributes.getOrNull(AuthenticatedAccessKeyIdKey)
                ?: throw S3Exception(S3ErrorCode.AccessDenied, "Access Denied")
        val allowed =
            config.database.withConnection { conn ->
                config.repository.hasBucketPermission(conn, accessKeyId, bucket, permission.name)
            }
        if (!allowed) {
            throw S3Exception(S3ErrorCode.AccessDenied, "Access Denied", resource = "/$bucket")
        }
    }

    fun authenticatedAccessKey(call: ApplicationCall): String? = call.attributes.getOrNull(AuthenticatedAccessKeyIdKey)
}
