import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `maven-publish`
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