package app.silofs.server

import io.ktor.server.application.Application
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import app.silofs.blob.RecoveryJob
import org.slf4j.LoggerFactory

fun Application.s3Module(config: ServerConfig) {
    installPlugins(config)
    val handlers = S3Handlers(config)
    val multipartHandlers = MultipartHandlers(config)
    s3Routes(handlers, multipartHandlers)
}

fun main() {
    val log = LoggerFactory.getLogger("Main")
    val config = ServerConfig.fromEnv()
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
        ).also { it.start() }
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
        server.stop(1000, 5000)
        recovery?.close()
        config.database.close()
    })
    server.start(wait = true)
}
