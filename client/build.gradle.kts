plugins {
    java
    `maven-publish`
    id("fabric-loom") version "1.9-SNAPSHOT"
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:1.21.4+build.8:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.14")
    include("com.squareup.okhttp3:okhttp:5.0.0-alpha.14")

    implementation("com.squareup.okio:okio:3.9.1")
    include("com.squareup.okio:okio:3.9.1")

    implementation("com.squareup.okio:okio-jvm:3.9.1")
    include("com.squareup.okio:okio-jvm:3.9.1")

    // JLayer - MP3 解码器（javax.sound 原生不支持 MP3）
    implementation("com.googlecode.soundlibs:jlayer:1.0.1.4")
    include("com.googlecode.soundlibs:jlayer:1.0.1.4")
}

tasks {
    processResources {
        filesMatching("fabric.mod.json") {
            expand("version" to project.version)
        }
    }

    jar {
        archiveFileName.set("MygoMusic-Client-${project.version}.jar")
    }

    remapJar {
        archiveFileName.set("MygoMusic-Client-${project.version}.jar")
    }
}
