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
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            artifactId = "graphkt-${artifactId}"
            pom {
                description.set("GraphKt client that uses browser fetch for HTTP requests.")
            }
        }
    }
}