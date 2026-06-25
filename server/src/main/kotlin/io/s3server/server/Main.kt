package app.silofs.server

import app.silofs.blob.BlobConsistencyChecker
import app.silofs.blob.FsBlobStore
import app.silofs.blob.RecoveryJob
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

fun Application.s3Module(config: ServerConfig) {
    installPlugins(config)
    val handlers = S3Handlers(config)
    val multipartHandlers = MultipartHandlers(config)
    s3Routes(config, handlers, multipartHandlers)
}

fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("Main")
    val config = ServerConfig.fromEnv()
    if (args.isNotEmpty()) {
        exitProcess(runAdminCommand(args, config))
    }
    log.info("Starting s3-server on ${config.bindHost}:${config.bindPort} region=${config.region} dataDir=${config.dataDir}")

    val recovery = if (config.recoveryConfig.enabled) {
        RecoveryJob(
            blobStore = config.blobStore,
            repo = config.repository,
            database = config.database,
            tempMaxAgeSeconds = config.recoveryConfig.tempMaxAgeSeconds,
            multipartMaxAgeSeconds = config.recoveryConfig.multipartMaxAgeSeconds,
            sweepIntervalSeconds = config.recoveryConfig.sweepIntervalSeconds,
            blobSweepIntervalSeconds = config.recoveryConfig.blobSweepIntervalSeconds
        ).also {
            runRecoveryOnce(it, config)
            it.start()
        }
    } else null

    val server = embeddedServer(
        factory = Netty,
        host = config.bindHost,
        port = config.bindPort
    ) {
        s3Module(config)
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down")
        config.operationalState.beginShutdown()
        server.stop(
            config.operationalConfig.shutdownQuietPeriodMs,
            config.operationalConfig.shutdownTimeoutMs,
        )
        val drained = config.operationalState.awaitRequestDrain(config.operationalConfig.shutdownTimeoutMs)
        if (!drained) {
            log.warn(
                "Shutdown drain timed out with {} in-flight request(s)",
                config.operationalState.inFlightRequests,
            )
        }
        recovery?.close()
        config.database.close()
    })
    server.start(wait = true)
}

internal fun runAdminCommand(args: Array<String>, config: ServerConfig): Int =
    when (args.toList()) {
        listOf("admin", "check-blobs") -> {
            val store = config.blobStore as? FsBlobStore
            if (store == null) {
                System.err.println("check-blobs requires FsBlobStore")
                2
            } else {
                val report = BlobConsistencyChecker(store, config.database).check()
                println(renderBlobConsistencyReport(report))
                if (report.isConsistent) 0 else 2
            }
        }
        listOf("admin", "recover-once") -> {
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
            0
        }
        else -> {
            System.err.println("usage: s3server admin check-blobs | admin recover-once")
            2
        }
    }.also {
        config.database.close()
    }

private fun runRecoveryOnce(job: RecoveryJob, config: ServerConfig) {
    try {
        job.runOnce()
        config.operationalState.recordRecoverySweep(success = true)
    } catch (t: Throwable) {
        config.operationalState.recordRecoverySweep(success = false)
        throw t
    }
}
