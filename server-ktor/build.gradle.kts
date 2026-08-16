plugins {
    id("graphkt.multiplatform-conventions")
}

kotlin {
    jvm()
    js(IR) { browser() }

    iosArm64()
    iosX64()
    iosSimulatorArm64()

    macosX64()
    macosArm64()

    linuxX64()
    linuxArm64()

    mingwX64()

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

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.server.test.host)
            }
        }
    }
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
