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
    add("testImplementation", "software.amazon.awssdk:apache-client:2.46.17")
    add("testImplementation", "aws.sdk.kotlin:s3:1.6.102")

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

val testSourceSet = extensions.getByType<SourceSetContainer>().named("test").get()

tasks.register<Test>("extendedCompatibilityTest") {
    group = "verification"
    description = "Runs the extended Docker-backed compatibility matrix with isolated Gradle test outputs."
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("app.silofs.compat.ExtendedCompatibilityTest")
    }
    timeout.set(Duration.ofMinutes(25))
    maxParallelForks = 1
    jvmArgs("-Xmx1g")
    binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/$name/binary"))
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/$name/xml"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/$name"))
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension>(
        "jacoco",
    ) {
        destinationFile =
            layout.buildDirectory
                .file("jacoco/$name.exec")
                .get()
                .asFile
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

tasks.named("test") {
    mustRunAfter(":integration-test:test")
}

tasks.named("extendedCompatibilityTest") {
    mustRunAfter(":integration-test:encryptionSmokeTest")
}
