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
                api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.0.0")
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.0.0")

                api(project(":common-runtime"))
            }
        }

        commonTest {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test:1.4.10")
                implementation("org.jetbrains.kotlin:kotlin-test-annotations-common:1.4.10")
            }
        }
    }
}