// This sample compiles generated GraphKt client and server code for JVM and Kotlin/Native targets.
//
// Run the sample against a local GraphKt snapshot:
//   ./gradlew snapshot
//   ./gradlew -p samples/native-direct allTests
val graphKtVersion: String by project

plugins {
    kotlin("multiplatform") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("com.steamstreet.graphkt")
}

kotlin {
    jvm()
    iosArm64()
    iosX64()
    iosSimulatorArm64()
    macosArm64()
    macosX64()
    linuxArm64()
    linuxX64()
    mingwX64()

    sourceSets {
        commonMain {
            dependencies {
                api("com.steamstreet:graphkt-client-direct:$graphKtVersion")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
    }
}

graphKt {
    schemaFiles.from(file("schema.graphql"))
    packageName.set("com.steamstreet.graphkt.samples.native")
}
