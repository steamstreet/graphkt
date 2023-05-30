plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    `maven-publish`
}

kotlin {
    js(IR) {
        browser {
        }
    }
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.serialization.json)
                implementation(project(":client"))
            }
        }

        val jsMain by getting {
            dependencies {
                implementation(libs.kotlin.coroutines)
            }
        }
    }
}