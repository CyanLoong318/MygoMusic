plugins {
    java
    `maven-publish`
}

repositories {
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:${property("spigot_api_version")}")
    compileOnly("me.clip:placeholderapi:2.11.6")
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.14")
    implementation("com.google.code.gson:gson:2.11.0")

    // HikariCP for database connection pool
    implementation("com.zaxxer:HikariCP:6.2.1")

    // SLF4J logging - provided by server
    compileOnly("org.slf4j:slf4j-api:2.0.16")
}

tasks {
    jar {
        archiveFileName.set("MygoMusic-${project.version}.jar")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    }

    processResources {
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
}
