plugins {
    kotlin("multiplatform")
    id("kotlinx-serialization")
    id("maven-publish")
}

kotlin {
    jvm()
    js { browser() }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core")

                api(project(":common-runtime"))
            }
        }
//
//        val jvmMain by getting {
//            dependencies {
//                api("org.jetbrains.kotlinx:kotlinx-serialization-runtime:0.20.0")
//            }
//        }
//        val jsMain by getting {
//            dependencies {
//                api("org.jetbrains.kotlinx:kotlinx-serialization-runtime-js:0.20.0")
//            }
//        }
    }
}
