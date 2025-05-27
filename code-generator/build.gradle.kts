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

        jvmMain {
            dependencies {
                api(libs.graphql)
                api(libs.kotlin.poet)
            }
        }

        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlin:kotlin-compiler:2.1.21")
                implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.21")

                // Add dependencies on common-runtime and server modules
                implementation(projects.commonRuntime)
                implementation(projects.server)
                implementation(projects.client)
                implementation(libs.kotlin.serialization.json)
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
