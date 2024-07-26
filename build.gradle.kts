plugins {
    id("io.github.gradle-nexus.publish-plugin") version "1.3.0"
}

allprojects {
    group = "com.steamstreet"

    val releaseName = findProperty("RELEASE_NAME") as? String
    version = releaseName?.removePrefix("v") ?: "1.0.0-${this.findProperty("BUILD_NUMBER")?.let { "build$it" } ?: "SNAPSHOT"}"
}

nexusPublishing {
    repositories {
        sonatype {
            username = findProperty("sonatypeUsername").toString()
            password = findProperty("sonatypePassword").toString()
        }
    }
}