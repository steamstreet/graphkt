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
        commonMain {
            dependencies {
                api(project(":client"))
                api(project(":server"))
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlin.serialization.json)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            artifactId = "graphkt-${artifactId}"
            pom {
                description.set("GraphKt client for in-process GraphQL execution")
            }
        }
    }
}
