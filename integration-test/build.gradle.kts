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
