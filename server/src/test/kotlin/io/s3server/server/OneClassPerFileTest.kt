package app.silofs.server

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class OneClassPerFileTest {
    @Test
    fun `new kotlin files keep one top level class per file`() {
        val root = findRepoRoot()
        val violations =
            Files.walk(root).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .filter { !it.toString().contains("${java.io.File.separator}build${java.io.File.separator}") }
                    .map { root.relativize(it).normalize().toString().replace('\\', '/') }
                    .filter { path -> topLevelClassCount(root.resolve(path)) > 1 && path !in BASELINE_ALLOWLIST }
                    .sorted()
                    .toList()
            }

        assertTrue(
            violations.isEmpty(),
            "One top-level class per Kotlin file is required outside the baseline allowlist: $violations",
        )
    }

    private fun findRepoRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("Could not find repository root")
        }
        return current
    }

    private fun topLevelClassCount(path: Path): Int =
        Files.readAllLines(path).count { TOP_LEVEL_CLASS.containsMatchIn(it) }

    companion object {
        private val TOP_LEVEL_CLASS =
            Regex("^(data\\s+|sealed\\s+|abstract\\s+|open\\s+|private\\s+)?(enum\\s+)?class\\s+")

        private val BASELINE_ALLOWLIST =
            setOf(
                "auth/src/main/kotlin/io/s3server/auth/Credentials.kt",
                "auth/src/main/kotlin/io/s3server/auth/SigV4.kt",
                "auth/src/main/kotlin/io/s3server/auth/SigV4AuthPlugin.kt",
                "auth/src/test/kotlin/io/s3server/auth/SigV4Test.kt",
                "blob/src/main/kotlin/io/s3server/blob/BlobConsistencyChecker.kt",
                "blob/src/main/kotlin/io/s3server/blob/FsBlobStore.kt",
                "common/src/main/kotlin/io/s3server/common/Models.kt",
                "common/src/test/kotlin/io/s3server/common/ETagMatcherTest.kt",
                "common/src/test/kotlin/io/s3server/common/S3XmlTest.kt",
                "metadata/src/main/kotlin/io/s3server/metadata/Database.kt",
                "metadata/src/main/kotlin/io/s3server/metadata/MetadataRepository.kt",
                "metadata/src/test/kotlin/io/s3server/metadata/JdbcMetadataRepositoryTest.kt",
                "server/src/main/kotlin/io/s3server/server/MultipartHandlers.kt",
                "server/src/main/kotlin/io/s3server/server/OperationalLimits.kt",
                "server/src/main/kotlin/io/s3server/server/RequestMetrics.kt",
                "server/src/main/kotlin/io/s3server/server/Security.kt",
                "server/src/main/kotlin/io/s3server/server/ServerConfig.kt",
                "server/src/test/kotlin/io/s3server/server/S3ServerRoutesTest.kt",
            )
    }
}

