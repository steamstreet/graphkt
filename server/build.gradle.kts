plugins {
    kotlin("multiplatform")
    id("kotlinx-serialization")
    id("maven-publish")
}

kotlin {
    jvm()
    js { browser() }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.0.0")
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.0.0")

                api(project(":common-runtime"))
            }
        }

        val jvmMain by getting {
            dependencies {
                api("com.graphql-java:graphql-java:2019-11-07T04-06-09-70d9412")
            }
        }
    }
}
