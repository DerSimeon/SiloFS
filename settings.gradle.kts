rootProject.name = "silofs"

include(":common")
include(":auth")
include(":metadata")
include(":blob")
include(":server")
include(":integration-test")
include(":compatibility-test")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
