package app.silofs.server

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
    log.info("Starting silofs on ${config.bindHost}:${config.bindPort} region=${config.region} dataDir=${config.dataDir}")

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
