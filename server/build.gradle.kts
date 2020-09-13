plugins {
    kotlin("multiplatform")
    id("kotlinx-serialization")
    id("maven-publish")
}

kotlin {
    jvm()
    js { browser() }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core")

                api(project(":common-runtime"))
            }
        }
    }
}
