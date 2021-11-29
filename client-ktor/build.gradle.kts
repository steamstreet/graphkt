plugins {
    kotlin("multiplatform")
    id("maven-publish")
    id("kotlinx-serialization")
}

kotlin {
    jvm()
    js(BOTH) { browser() }

    sourceSets {
        commonMain {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core")
                api(project(":client"))

                api("io.ktor:ktor-client-core:1.4.0")
                api("io.ktor:ktor-http:1.4.0")
            }
        }
    }
}