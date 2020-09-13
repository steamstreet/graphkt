plugins {
    kotlin("multiplatform")
    id("kotlinx-serialization")
    id("maven-publish")
}

kotlin {
    jvm()
    js {
        browser {
            testTask {
                enabled = false
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core")

                api(project(":common-runtime"))
            }
        }
    }
}