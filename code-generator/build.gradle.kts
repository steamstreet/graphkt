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
                // Used to compile generated code in tests; must match the Kotlin version the runtime modules
                // are built with, or the in-process compiler cannot read their metadata.
                implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.0")

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
