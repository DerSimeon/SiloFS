plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    add("api", project(":common"))
    add("implementation", "org.slf4j:slf4j-api:2.1.0-alpha1")
    add("api", "io.ktor:ktor-http:3.5.0")
    add("api", "io.ktor:ktor-server-core:3.5.0")
    add("implementation", "io.ktor:ktor-utils:3.5.0")
    add("testImplementation", platform("org.junit:junit-bom:6.1.0"))
    add("testImplementation", "org.junit.jupiter:junit-jupiter")
    add("testImplementation", "io.kotest:kotest-assertions-core:6.2.1")
    add("testImplementation", "io.mockk:mockk:1.14.11")
}
