plugins {
    id("graphkt.multiplatform-conventions")
}

kotlin {
    js(IR) {
        browser {
        }
    }

    explicitApi()

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