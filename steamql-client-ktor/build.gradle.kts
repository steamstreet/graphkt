plugins {
    kotlin("multiplatform")
    id("maven-publish")
    id("kotlinx-serialization")
}

kotlin {
    jvm()
    js { browser() }
}

dependencies {
    commonMainImplementation("org.jetbrains.kotlin:kotlin-stdlib-common")
    commonMainImplementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime-common")
    commonMainImplementation(project(":steamql-client"))
    commonMainApi("io.ktor:ktor-client-core:1.2.4")
    commonMainApi("io.ktor:ktor-http:1.2.4")

    "jvmMainImplementation"("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    "jvmMainImplementation"("com.fasterxml.jackson.module:jackson-module-kotlin")
    "jvmMainImplementation"("org.jetbrains.kotlinx:kotlinx-serialization-runtime")
    "jvmMainApi"("io.ktor:ktor-client-core-jvm:1.2.4")
    "jvmMainApi"("io.ktor:ktor-http-jvm:1.2.4")

    "jsMainImplementation"("org.jetbrains.kotlin:kotlin-stdlib-js")
    "jsMainImplementation"("org.jetbrains.kotlinx:kotlinx-serialization-runtime-js")

    "jsMainApi"("io.ktor:ktor-client-core-js:1.2.4")
    "jsMainApi"("io.ktor:ktor-http-js:1.2.4")
}
tasks["jsBrowserWebpack"].enabled = false
tasks["jsBrowserProductionWebpack"].enabled = false