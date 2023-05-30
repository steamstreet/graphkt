plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    `maven-publish`
}

kotlin {
    jvm()
    js(IR) { browser() }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlin.serialization.json)

                api(project(":common-runtime"))
            }
        }

        val jvmMain by getting {
            dependencies {
                api(libs.graphql)
            }
        }
    }
}
