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

        val jvmMain by getting {
            dependencies {
                api(libs.graphql)
                api(libs.kotlin.poet)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}


publishing {
    publications {
        withType<MavenPublication> {
            artifactId = "graphkt-${artifactId}"
            pom {
                description.set("GraphKt code generation library.")
            }
        }
    }
}