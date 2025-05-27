plugins {
    id("graphkt.multiplatform-conventions")
}

kotlin {
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":client"))
                api(libs.kotlin.serialization.core)
                api(libs.ktor.client.core)
            }
        }

        jvmMain {
            dependencies {
                implementation(libs.graphql)
                implementation(projects.server)
            }
        }
    }
}

//publishing {
//    publications {
//        withType<MavenPublication> {
//            artifactId = "graphkt-${artifactId}"
//            pom {
//                description.set("GraphKt client that uses KTOR for HTTP requests.")
//            }
//        }
//    }
//}