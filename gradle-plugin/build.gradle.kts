@file:Suppress("UnstableApiUsage")

plugins {
    kotlin("jvm")
    id("java-gradle-plugin")
    `maven-publish`
}


kotlin {
    jvmToolchain(11)
}

dependencies {
    api(libs.graphql)
    api(libs.kotlin.poet)
    api(project(":code-generator"))
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        create("graphkt") {
            id = "com.steamstreet.graphkt"
            implementationClass = "com.steamstreet.graphkt.generator.GraphQLGeneratorPlugin"
        }
    }
}