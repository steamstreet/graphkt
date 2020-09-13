plugins {
    kotlin("multiplatform")
    id("maven-publish")
    id("kotlinx-serialization")
}

kotlin {
    jvm()
    js { browser() }

    sourceSets {
        commonMain {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core")
                implementation(project(":client"))

                api("io.ktor:ktor-client-core:1.4.0")
                api("io.ktor:ktor-http:1.4.0")
            }
        }
    }
}

tasks["jsBrowserWebpack"].enabled = false
tasks["jsBrowserProductionWebpack"].enabled = false
