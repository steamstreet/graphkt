import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    `maven-publish`
    signing
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = freeCompilerArgs + "-Xcontext-receivers"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}