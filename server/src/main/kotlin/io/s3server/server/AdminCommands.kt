package app.silofs.server

import app.silofs.blob.BlobConsistencyChecker
import app.silofs.blob.FsBlobStore
import app.silofs.blob.RecoveryJob
import app.silofs.metadata.AccessKeyRecord
import app.silofs.metadata.AuditEventRecord
import java.time.Instant

internal fun runAdminCommand(args: Array<String>, config: ServerConfig): Int =
    try {
        when {
            args.toList() == listOf("admin", "check-blobs") -> checkBlobs(config)
            args.toList() == listOf("admin", "recover-once") -> recoverOnce(config)
            args.size >= 3 && args[0] == "admin" && args[1] == "access-key" -> accessKeyCommand(args.drop(2), config)
            else -> usage()
        }
    } finally {
        config.database.close()
    }

private fun checkBlobs(config: ServerConfig): Int {
    val store = config.blobStore as? FsBlobStore
    if (store == null) {
        System.err.println("check-blobs requires FsBlobStore")
        return 2
    }
    val report = BlobConsistencyChecker(store, config.database).check()
    println(renderBlobConsistencyReport(report))
    return if (report.isConsistent) 0 else 2
}

private fun recoverOnce(config: ServerConfig): Int {
    RecoveryJob(
        blobStore = config.blobStore,
        repo = config.repository,
        database = config.database,
        tempMaxAgeSeconds = config.recoveryConfig.tempMaxAgeSeconds,
        multipartMaxAgeSeconds = config.recoveryConfig.multipartMaxAgeSeconds,
        sweepIntervalSeconds = config.recoveryConfig.sweepIntervalSeconds,
        blobSweepIntervalSeconds = config.recoveryConfig.blobSweepIntervalSeconds,
    ).use { runRecoveryOnce(it, config) }
    println("recovery=ok")
    return 0
}

private fun accessKeyCommand(args: List<String>, config: ServerConfig): Int =
    when (args.firstOrNull()) {
        "create" -> createAccessKey(args.drop(1), config)
        "list" -> listAccessKeys(config)
        "disable" -> setAccessKeyState(args, config, "DISABLED")
        "enable" -> setAccessKeyState(args, config, "ACTIVE")
        "rotate" -> rotateAccessKey(args, config)
        "delete" -> deleteAccessKey(args, config)
        "reencrypt" -> reencryptAccessKeys(config)
        else -> usage()
    }

private fun createAccessKey(args: List<String>, config: ServerConfig): Int {
    val options = parseOptions(args)
    val accessKeyId = options["access-key-id"] ?: AccessKeyGenerator.accessKeyId()
    val secret = AccessKeyGenerator.secretAccessKey()
    val description = options["description"]
    config.database.withConnection { conn ->
        config.repository.upsertAccessKeyRecord(
            conn,
            accessKeyRecordForSecret(accessKeyId, secret, description, config.securityConfig)
        )
        config.repository.insertAuditEvent(conn, adminAudit("AdminAccessKeyCreate", accessKeyId))
    }
    println("access_key_id=$accessKeyId")
    println("secret_access_key=$secret")
    println("state=ACTIVE")
    return 0
}

private fun listAccessKeys(config: ServerConfig): Int {
    val keys = config.database.withConnection { conn -> config.repository.listAccessKeys(conn) }
    println("access_key_id\tstate\tencrypted\tdescription\tcreated_at\tupdated_at")
    keys.forEach { key ->
        println(
            listOf(
                key.accessKeyId,
                key.state,
                (key.secretCiphertext != null).toString(),
                key.description ?: "",
                key.createdAt ?: "",
                key.updatedAt ?: "",
            ).joinToString("\t")
        )
    }
    return 0
}

private fun setAccessKeyState(args: List<String>, config: ServerConfig, state: String): Int {
    val accessKeyId = args.getOrNull(1) ?: return usage()
    val updated = config.database.withConnection { conn ->
        val ok = config.repository.updateAccessKeyState(conn, accessKeyId, state)
        if (ok) config.repository.insertAuditEvent(conn, adminAudit("AdminAccessKey$state", accessKeyId))
        ok
    }
    if (!updated) {
        System.err.println("access key not found: $accessKeyId")
        return 2
    }
    println("access_key_id=$accessKeyId")
    println("state=$state")
    return 0
}

private fun rotateAccessKey(args: List<String>, config: ServerConfig): Int {
    val accessKeyId = args.getOrNull(1) ?: return usage()
    val secret = AccessKeyGenerator.secretAccessKey()
    val updated = config.database.withConnection { conn ->
        val current = config.repository.listAccessKeys(conn, includeDeleted = false)
            .firstOrNull { it.accessKeyId == accessKeyId }
            ?: return@withConnection false
        val next = accessKeyRecordForSecret(
            accessKeyId = accessKeyId,
            secret = secret,
            description = current.description,
            securityConfig = config.securityConfig,
            rotated = true,
        ).copy(state = current.state, disabledAt = current.disabledAt)
        config.repository.upsertAccessKeyRecord(conn, next)
        config.repository.insertAuditEvent(conn, adminAudit("AdminAccessKeyRotate", accessKeyId))
        true
    }
    if (!updated) {
        System.err.println("access key not found: $accessKeyId")
        return 2
    }
    println("access_key_id=$accessKeyId")
    println("secret_access_key=$secret")
    return 0
}

private fun deleteAccessKey(args: List<String>, config: ServerConfig): Int {
    val accessKeyId = args.getOrNull(1) ?: return usage()
    val deleted = config.database.withConnection { conn ->
        val ok = config.repository.softDeleteAccessKey(conn, accessKeyId)
        if (ok) config.repository.insertAuditEvent(conn, adminAudit("AdminAccessKeyDelete", accessKeyId))
        ok
    }
    if (!deleted) {
        System.err.println("access key not found: $accessKeyId")
        return 2
    }
    println("access_key_id=$accessKeyId")
    println("state=DELETED")
    return 0
}

private fun reencryptAccessKeys(config: ServerConfig): Int {
    if (config.securityConfig.secretCodec == null) {
        System.err.println("reencrypt requires S3_ACCESS_KEY_SECRET_ENCRYPTION_KEY")
        return 2
    }
    var count = 0
    config.database.withConnection { conn ->
        config.repository.listAccessKeys(conn, includeDeleted = false).forEach { key ->
            val secret = key.secretForAuth(config.securityConfig) ?: key.secretAccessKey ?: return@forEach
            val updated = accessKeyRecordForSecret(
                accessKeyId = key.accessKeyId,
                secret = secret,
                description = key.description,
                securityConfig = config.securityConfig,
            ).copy(state = key.state, disabledAt = key.disabledAt, rotatedAt = key.rotatedAt)
            config.repository.upsertAccessKeyRecord(conn, updated)
            count++
        }
        config.repository.insertAuditEvent(conn, adminAudit("AdminAccessKeyReencrypt", null, """{"count":$count}"""))
    }
    println("reencrypted=$count")
    return 0
}

private fun adminAudit(operation: String, accessKeyId: String?, detailJson: String = "{}"): AuditEventRecord =
    AuditEventRecord(
        requestId = null,
        accessKeyId = accessKeyId,
        operation = operation,
        status = 0,
        latencyMs = 0,
        source = "admin",
        detailJson = detailJson,
    )

private fun parseOptions(args: List<String>): Map<String, String> {
    val out = linkedMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val raw = args[i]
        if (!raw.startsWith("--")) {
            i++
            continue
        }
        val key = raw.removePrefix("--")
        val value = args.getOrNull(i + 1)?.takeIf { !it.startsWith("--") } ?: ""
        out[key] = value
        i += if (value.isEmpty()) 1 else 2
    }
    return out
}

private fun usage(): Int {
    System.err.println(
        "usage: silofs admin check-blobs | admin recover-once | " +
            "admin access-key create [--access-key-id ID] [--description TEXT] | " +
            "admin access-key list | admin access-key disable ID | admin access-key enable ID | " +
            "admin access-key rotate ID | admin access-key delete ID | admin access-key reencrypt"
    )
    return 2
}

internal fun runRecoveryOnce(job: RecoveryJob, config: ServerConfig) {
    try {
        job.runOnce()
        config.operationalState.recordRecoverySweep(success = true)
    } catch (t: Throwable) {
        config.operationalState.recordRecoverySweep(success = false)
        throw t
    }
}
