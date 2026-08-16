plugins {
    id("graphkt.multiplatform-conventions")
}

@Suppress("UNUSED_VARIABLE")
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
                api(libs.kotlin.serialization.json)

                api(project(":common-runtime"))
                api(libs.kotlinx.coroutines.core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val jvmMain by getting {
            dependencies {
                api(libs.graphql)
            }
        }
    }
}


publishing {
    publications {
        withType<MavenPublication> {
            artifactId = "graphkt-${artifactId}"
            pom {
                description.set("Common code for all GraphKt server implementations")
            }
        }
    }
}

val jvmTestTask = tasks.named<Test>("jvmTest")

tasks.register<Test>("performanceBaseline") {
    group = "verification"
    description = "Records non-gating GraphQL runtime performance baselines"
    dependsOn("jvmTestClasses")
    testClassesDirs = jvmTestTask.get().testClassesDirs
    classpath = jvmTestTask.get().classpath
    filter {
        includeTestsMatching("com.steamstreet.graphkt.server.execution.GraphQLPerformanceBaseline")
    }
    systemProperty("graphkt.performance.baseline", "true")
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
    }
}
