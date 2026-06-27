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
                    .map {
                        root
                            .relativize(it)
                            .normalize()
                            .toString()
                            .replace('\\', '/')
                    }.filter { path -> topLevelClassCount(root.resolve(path)) > 1 && path !in BASELINE_ALLOWLIST }
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

    private fun topLevelClassCount(path: Path): Int = Files.readAllLines(path).count { TOP_LEVEL_CLASS.containsMatchIn(it) }

    companion object {
        private val TOP_LEVEL_CLASS =
            Regex("^(data\\s+|sealed\\s+|abstract\\s+|open\\s+|private\\s+)?(enum\\s+)?class\\s+")

        private val BASELINE_ALLOWLIST =
            setOf(
                "auth/src/main/kotlin/app/silofs/auth/Credentials.kt",
                "auth/src/main/kotlin/app/silofs/auth/SigV4.kt",
                "auth/src/main/kotlin/app/silofs/auth/SigV4AuthPlugin.kt",
                "auth/src/test/kotlin/app/silofs/auth/SigV4Test.kt",
                "blob/src/main/kotlin/app/silofs/blob/BlobConsistencyChecker.kt",
                "blob/src/main/kotlin/app/silofs/blob/FsBlobStore.kt",
                "common/src/main/kotlin/app/silofs/common/Models.kt",
                "common/src/test/kotlin/app/silofs/common/ETagMatcherTest.kt",
                "common/src/test/kotlin/app/silofs/common/S3XmlTest.kt",
                "metadata/src/main/kotlin/app/silofs/metadata/Database.kt",
                "metadata/src/main/kotlin/app/silofs/metadata/MetadataRepository.kt",
                "metadata/src/test/kotlin/app/silofs/metadata/JdbcMetadataRepositoryTest.kt",
                "server/src/main/kotlin/app/silofs/server/MultipartHandlers.kt",
                "server/src/main/kotlin/app/silofs/server/OperationalLimits.kt",
                "server/src/main/kotlin/app/silofs/server/RequestMetrics.kt",
                "server/src/main/kotlin/app/silofs/server/Security.kt",
                "server/src/main/kotlin/app/silofs/server/ServerConfig.kt",
                "server/src/test/kotlin/app/silofs/server/S3ServerRoutesTest.kt",
            )
    }
}
