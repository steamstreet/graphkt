plugins {
    id("graphkt.multiplatform-conventions")
}

kotlin {
    jvm()
    js(IR) {
        browser {
            testTask {
                enabled = false
            }
        }
    }
    iosArm64()
    iosX64()
    iosSimulatorArm64()

    explicitApi()

    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlin.serialization.json)

                api(project(":common-runtime"))
            }
        }

        commonTest {
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
                description.set("Common code for all GraphKt client implementations")
            }
        }
    }
}