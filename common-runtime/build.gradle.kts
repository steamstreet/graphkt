plugins {
    kotlin("multiplatform")
    id("maven-publish")
}

kotlin {
    jvm()
    js { browser() }
}

dependencies {
    "commonMainImplementation"("org.jetbrains.kotlin:kotlin-stdlib-common")
    "commonMainImplementation"("org.jetbrains.kotlinx:kotlinx-serialization-runtime-common")

    "jvmMainImplementation"("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    "jvmMainImplementation"("com.fasterxml.jackson.module:jackson-module-kotlin")
    "jvmMainImplementation"("org.jetbrains.kotlinx:kotlinx-serialization-runtime")

    "jsMainImplementation"("org.jetbrains.kotlin:kotlin-stdlib-js")
    "jsMainImplementation"("org.jetbrains.kotlinx:kotlinx-serialization-runtime-js")
}
