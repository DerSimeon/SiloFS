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
    add("testImplementation", "software.amazon.awssdk:apache-client:2.46.17")

    add("testImplementation", platform("org.junit:junit-bom:6.1.0"))
    add("testImplementation", "org.junit.jupiter:junit-jupiter")
    add("testImplementation", "io.ktor:ktor-server-netty:3.5.0")
    add("testImplementation", "org.testcontainers:postgresql:1.21.4")
    add("testImplementation", "org.testcontainers:junit-jupiter:1.21.4")
    add("testImplementation", "org.slf4j:slf4j-simple:2.1.0-alpha1")
}

tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(25))
    maxParallelForks = 1
    jvmArgs("-Xmx1g")
}
