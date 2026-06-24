plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    add("implementation", "org.slf4j:slf4j-api:2.1.0-alpha1")
    add("testImplementation", platform("org.junit:junit-bom:6.1.0"))
    add("testImplementation", "org.junit.jupiter:junit-jupiter")
    add("testImplementation", "io.kotest:kotest-assertions-core:6.2.1")
}
