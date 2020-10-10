plugins {
    kotlin("multiplatform")
    id("maven-publish")
    id("kotlinx-serialization")
}

kotlin {
    js {
        browser {
//            val main by compilations.getting {
//                kotlinOptions {
//                    // Setup the Kotlin compiler options for the 'main' compilation:
//                    freeCompilerArgs = listOf("-Xopt-in=kotlin.RequiresOptIn")
//
//                }
//            }
        }
    }
    sourceSets {
        all {
            languageSettings.apply {
                useExperimentalAnnotation("kotlin.RequiresOptIn")
            }
        }

        commonMain {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
                implementation(project(":client"))
            }
        }

        val jsMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
            }
        }
    }
}