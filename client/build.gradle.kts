plugins {
    id("graphkt.multiplatform-conventions")
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

    explicitApi()

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