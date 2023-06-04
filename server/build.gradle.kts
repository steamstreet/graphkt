plugins {
    id("graphkt.multiplatform-conventions")
}

@Suppress("UNUSED_VARIABLE")
kotlin {
    jvm()
    js(IR) { browser() }

    explicitApi()

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
