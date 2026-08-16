// A GraphKt client consumed from Kotlin/Native targets, plus JVM for comparison.
//
// Run against a locally published build of GraphKt:
//   ./gradlew snapshot                                (from the repository root; publishes x.y.z-SNAPSHOT to mavenLocal)
//   ./gradlew -p samples/native-client allTests       (runs the tests for every target that can run on this host)
//   ./gradlew -p samples/native-client linuxX64TestBinaries
//   docker run --rm --platform linux/amd64 \
//     -v "$PWD/samples/native-client/build/bin/linuxX64/debugTest:/t:ro" debian:bookworm-slim /t/test.kexe
//
// The GraphKt version is set by `graphKtVersion` in gradle.properties (override with -PgraphKtVersion=...).
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
    linuxX64()
    linuxArm64()
    macosArm64()
    macosX64()
    mingwX64()

    sourceSets {
        commonMain {
            dependencies {
                api("com.steamstreet:graphkt-client-ktor:$graphKtVersion")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("io.ktor:ktor-client-mock:3.3.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
    }
}

graphKt {
    schemaFiles.from(file("schema.graphql"))
    packageName.set("com.steamstreet.graphkt.samples.basic")
    server {
        // This sample checks only the generated client API. See native-direct for both API families.
        enabled.set(false)
    }
}
