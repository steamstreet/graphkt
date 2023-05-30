plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    `maven-publish`
}

kotlin {
    jvm()
    js(IR) {
        browser {
            testTask {
                enabled = false
            }
        }
    }
    ios {}

    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlin.serialization.json)

                api(project(":common-runtime"))
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}