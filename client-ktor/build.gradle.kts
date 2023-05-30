plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    `maven-publish`
}

kotlin {
    jvm()
    js(IR) { browser() }

    ios {

    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":client"))
                api(libs.kotlin.serialization.core)
                api(libs.ktor.client.core)
            }
        }
    }
}