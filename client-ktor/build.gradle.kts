plugins {
    id("graphkt.multiplatform-conventions")
}

kotlin {
    jvm()
    js(IR) { browser() }

    ios {
    }

    explicitApi()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":client"))
                api(libs.kotlin.serialization.core)
                api(libs.ktor.client.core)
            }
        }
    }
}