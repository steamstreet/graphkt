plugins {
    id("graphkt.multiplatform-conventions")
}

kotlin {
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.serialization.json)
            }
        }

        @Suppress("UNUSED_VARIABLE")
        val jvmMain by getting {
            dependencies {
                api(libs.graphql)
                api(libs.kotlin.poet)
            }
        }
    }
}