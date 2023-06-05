plugins {
    id("graphkt.multiplatform-conventions")
}

kotlin {
    jvm()

    explicitApi()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlin.serialization.core)

                api(project(":common-runtime"))
                api(project(":server"))
                implementation(libs.ktor.server.core)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(libs.graphql)
            }
        }
    }
}

kotlin {
    jvmToolchain(11)
}


publishing {
    publications {
        withType<MavenPublication> {
            artifactId = "graphkt-${artifactId}"
            pom {
                description.set("GraphKt server utilizing KTOR")
            }
        }
    }
}