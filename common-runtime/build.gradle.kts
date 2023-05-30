plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    `maven-publish`
}

kotlin {
    jvm()
    js(IR) { browser() }

    ios {}

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.serialization.json)
            }
        }
    }
}