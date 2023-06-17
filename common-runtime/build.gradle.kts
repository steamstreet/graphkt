plugins {
    id("graphkt.multiplatform-conventions")
}

kotlin {
    jvm()
    js(IR) { browser() }

    iosArm64()
    iosX64()
    iosSimulatorArm64()

    explicitApi()

    sourceSets {
        commonMain {
            dependencies {
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
                description.set("Common runtime used for clients and servers of GraphKt")
            }
        }
    }
}