plugins {
    kotlin("multiplatform")
    id("kotlinx-serialization")
    id("maven-publish")
}

kotlin {
    jvm()
    js(BOTH) { browser() }

    sourceSets {
        commonMain {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.0.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.0.0")
            }
        }
    }
}