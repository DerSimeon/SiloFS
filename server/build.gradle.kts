plugins {
    kotlin("jvm")
    `java-library`
    application
}

application {
    mainClass.set("app.silofs.server.MainKt")
    applicationName = "silofs"
}

dependencies {
    add("implementation", project(":common"))
    add("implementation", project(":auth"))
    add("implementation", project(":metadata"))
    add("implementation", project(":blob"))
    add("implementation", "org.slf4j:slf4j-api:2.1.0-alpha1")
    add("implementation", "ch.qos.logback:logback-classic:1.5.35")
    add("implementation", "io.ktor:ktor-server-core:3.5.0")
    add("implementation", "io.ktor:ktor-server-netty:3.5.0")
    add("implementation", "io.ktor:ktor-server-call-logging:3.5.0")
    add("implementation", "io.ktor:ktor-server-status-pages:3.5.0")
    add("implementation", "io.ktor:ktor-server-content-negotiation:3.5.0")
    add("implementation", "io.ktor:ktor-serialization-kotlinx-json:3.5.0")
    add("implementation", "io.ktor:ktor-server-cors:3.5.0")
    add("implementation", "io.ktor:ktor-server-default-headers:3.5.0")
    add("implementation", "io.ktor:ktor-server-partial-content:3.5.0")
    add("implementation", "io.ktor:ktor-server-host-common:3.5.0")
    add("implementation", "io.ktor:ktor-server-sse:3.5.0")
    add("implementation", "io.ktor:ktor-utils:3.5.0")
    add("implementation", "io.ktor:ktor-http:3.5.0")
    add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    add("implementation", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    add("testImplementation", platform("org.junit:junit-bom:6.1.0"))
    add("testImplementation", "org.junit.jupiter:junit-jupiter")
    add("testImplementation", "io.kotest:kotest-assertions-core:6.2.1")
    add("testImplementation", "io.mockk:mockk:1.14.11")
    add("testImplementation", "io.ktor:ktor-server-test-host:3.5.0")
    add("testImplementation", "org.testcontainers:postgresql:1.21.4")
    add("testImplementation", "org.testcontainers:junit-jupiter:1.21.4")
    add("testImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
