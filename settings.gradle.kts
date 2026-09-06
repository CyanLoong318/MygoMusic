pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "mygomusic"

include("plugin")
include("client")
