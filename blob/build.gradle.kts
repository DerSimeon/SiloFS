plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    add("api", project(":common"))
    add("implementation", project(":metadata"))
    add("implementation", "org.slf4j:slf4j-api:2.1.0-alpha1")
    add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    add("testImplementation", platform("org.junit:junit-bom:6.1.0"))
    add("testImplementation", "org.junit.jupiter:junit-jupiter")
    add("testImplementation", "io.kotest:kotest-assertions-core:6.2.1")
    add("testImplementation", "io.mockk:mockk:1.14.11")
    add("testImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    add("testImplementation", "org.testcontainers:postgresql:1.21.4")
    add("testImplementation", "org.testcontainers:junit-jupiter:1.21.4")
}
