import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import java.time.Duration

plugins {
    kotlin("jvm")
}

dependencies {
    add("testImplementation", project(":server"))
    add("testImplementation", project(":common"))
    add("testImplementation", project(":auth"))
    add("testImplementation", project(":metadata"))
    add("testImplementation", project(":blob"))

    add("testImplementation", "software.amazon.awssdk:s3:2.46.17")
    add("testImplementation", "software.amazon.awssdk:netty-nio-client:2.46.17")
    add("testImplementation", "software.amazon.awssdk:apache-client:2.46.17")

    add("testImplementation", platform("org.junit:junit-bom:6.1.0"))
    add("testImplementation", "org.junit.jupiter:junit-jupiter")
    add("testImplementation", "io.kotest:kotest-assertions-core:6.2.1")
    add("testImplementation", "io.ktor:ktor-server-test-host:3.5.0")
    add("testImplementation", "io.ktor:ktor-server-netty:3.5.0")
    add("testImplementation", "org.testcontainers:postgresql:1.21.4")
    add("testImplementation", "org.testcontainers:junit-jupiter:1.21.4")
    add("testImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    add("testImplementation", "org.slf4j:slf4j-simple:2.1.0-alpha1")
}

tasks.withType<Test>().configureEach {
    // The integration tests boot a real Ktor server and a real Postgres via
    // testcontainers; they are slow so we give them more headroom.
    timeout.set(Duration.ofMinutes(15))
    // These tests share Docker Desktop/Testcontainers and several tests launch
    // crash-only server processes. Keep the suite deterministic instead of
    // racing Docker discovery across Gradle forks.
    maxParallelForks = 1
    // Increase heap for the AWS SDK + testcontainers combo.
    jvmArgs("-Xmx1g")
}

val testSourceSet = extensions.getByType<SourceSetContainer>().named("test").get()

fun registerFocusedIntegrationTest(
    taskName: String,
    className: String,
    descriptionText: String,
) {
    tasks.register<Test>(taskName) {
        group = "verification"
        description = descriptionText
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform()
        filter {
            includeTestsMatching(className)
        }
        timeout.set(Duration.ofMinutes(15))
        maxParallelForks = 1
        jvmArgs("-Xmx1g")
        binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/$taskName/binary"))
        reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/$taskName/xml"))
        reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/$taskName"))
        extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension>(
            "jacoco",
        ) {
            destinationFile =
                layout.buildDirectory
                    .file("jacoco/$taskName.exec")
                    .get()
                    .asFile
            isIncludeNoLocationClasses = true
            excludes = listOf("jdk.internal.*")
        }
    }
}

registerFocusedIntegrationTest(
    taskName = "failpointCrashTest",
    className = "app.silofs.test.S3ServerFailpointCrashTest",
    descriptionText = "Runs crash/failpoint durability tests with isolated Gradle test outputs.",
)

registerFocusedIntegrationTest(
    taskName = "concurrencyTest",
    className = "app.silofs.test.S3ServerConcurrencyTest",
    descriptionText = "Runs focused concurrency/race tests with isolated Gradle test outputs.",
)

registerFocusedIntegrationTest(
    taskName = "loadSmokeTest",
    className = "app.silofs.test.S3ServerLoadSmokeTest",
    descriptionText = "Runs the production load smoke test with isolated Gradle test outputs.",
)

registerFocusedIntegrationTest(
    taskName = "encryptionSmokeTest",
    className = "app.silofs.test.S3ServerEncryptionTest",
    descriptionText = "Runs encryption-at-rest integration tests with isolated Gradle test outputs.",
)

tasks.named("concurrencyTest") {
    mustRunAfter(tasks.named("failpointCrashTest"))
}

tasks.named("loadSmokeTest") {
    mustRunAfter(tasks.named("concurrencyTest"))
}

tasks.named("encryptionSmokeTest") {
    mustRunAfter(tasks.named("loadSmokeTest"))
}
