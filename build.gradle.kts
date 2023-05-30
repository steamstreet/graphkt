plugins {
    kotlin("multiplatform") version "1.8.21" apply false
    kotlin("plugin.serialization") version "1.8.21" apply false
}

subprojects {
    apply(plugin = "maven-publish")

    group = "com.steamstreet.graphkt"
    version = "0.5.1-${this.findProperty("BUILD_NUMBER")?.let { "build$it" } ?: "SNAPSHOT"}"

    configure<PublishingExtension> {
        repositories {
            maven {
                url = uri("s3://graphkt-releases/maven")
                authentication {
                    val awsIm by registering(AwsImAuthentication::class)
                }
            }
        }
    }
}