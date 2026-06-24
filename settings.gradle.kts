rootProject.name = "s3-server"

include(":common")
include(":auth")
include(":metadata")
include(":blob")
include(":server")
include(":integration-test")

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
