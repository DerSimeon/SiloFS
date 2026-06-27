import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    kotlin("jvm") version "2.4.20-Beta1" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
    jacoco
}

allprojects {
    group = "app.silofs"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "jacoco")

    // Pull versions from gradle.properties.
    val coroutinesVersion = rootProject.property("coroutinesVersion") as String
    val slf4jVersion = rootProject.property("slf4jVersion") as String
    val junitVersion = rootProject.property("junitVersion") as String
    val kotestVersion = rootProject.property("kotestVersion") as String
    val mockkVersion = rootProject.property("mockkVersion") as String

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
        jvmToolchain(21)
        compilerOptions {
            freeCompilerArgs.add("-Xjsr305=strict")
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
        }
    }

    dependencies {
        add("implementation", platform("org.jetbrains.kotlinx:kotlinx-coroutines-bom:$coroutinesVersion"))
        add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-core")
        add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-jdk8")
        add("implementation", "org.slf4j:slf4j-api:$slf4jVersion")
        add("testImplementation", platform("org.junit:junit-bom:$junitVersion"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter-api")
        add("testImplementation", "org.junit.jupiter:junit-jupiter-params")
        add("testRuntimeOnly", "org.junit.jupiter:junit-jupiter-engine")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        add("testImplementation", "io.kotest:kotest-assertions-core:$kotestVersion")
        add("testImplementation", "io.kotest:kotest-assertions-json:$kotestVersion")
        add("testImplementation", "io.mockk:mockk:$mockkVersion")
        add("testImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-test")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("FAILED", "SKIPPED", "STANDARD_ERROR")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = false
        }
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        if (name == "test") {
            finalizedBy(tasks.named("jacocoTestReport"))
        }
        extensions.configure<JacocoTaskExtension>("jacoco") {
            isIncludeNoLocationClasses = true
            excludes = listOf("jdk.internal.*")
        }
    }

    tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
    }

    // Enforce 90% line coverage on every module.
    tasks.register<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoCoverageVerification") {
        executionData.from(tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport").get().executionData)
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    minimum = "0.90".toBigDecimal()
                }
                limit {
                    counter = "BRANCH"
                    minimum = "0.85".toBigDecimal()
                }
            }
        }
    }

    tasks.named("check") {
        dependsOn("jacocoCoverageVerification")
    }

    extensions.configure<DetektExtension>("detekt") {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(files("$rootDir/detekt.yml"))
    }

    extensions.configure<KtlintExtension>("ktlint") {
        version.set("1.4.1")
        filter {
            exclude { it.file.path.contains("/generated/") }
        }
    }
}

tasks.register("dockerBackedVerification") {
    group = "verification"
    description = "Runs the full Docker-backed integration and compatibility suites in a deterministic order."
    dependsOn(":integration-test:test", ":compatibility-test:test")
}

tasks.register("productionFocusedVerification") {
    group = "verification"
    description = "Runs focused production-readiness checks with isolated test output directories."
    dependsOn(
        ":integration-test:failpointCrashTest",
        ":integration-test:concurrencyTest",
        ":integration-test:loadSmokeTest",
        ":integration-test:encryptionSmokeTest",
        ":compatibility-test:extendedCompatibilityTest",
    )
}
