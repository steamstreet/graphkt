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
                implementation(kotlin("stdlib-common"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core")
                implementation(project(":client"))
            }
        }

        val jsMain by getting {
            dependencies {
                implementation(kotlin("stdlib-js"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
            }
        }
    }
}
//
//dependencies {
//    commonMainImplementation("org.jetbrains.kotlin:kotlin-stdlib-common")
//    commonMainImplementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime-common")
//    commonMainImplementation(project(":client"))
//
//    "jsMainImplementation"("org.jetbrains.kotlin:kotlin-stdlib-js")
//    "jsMainImplementation"("org.jetbrains.kotlinx:kotlinx-serialization-runtime-js")
//    "jsMainImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core-js")
//
//}