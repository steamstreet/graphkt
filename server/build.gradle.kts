plugins {
    kotlin("multiplatform")
    id("kotlinx-serialization")
    id("maven-publish")
}

kotlin {
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.0.0")
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.0.0")

                api(project(":common-runtime"))
            }
        }
    }
}
