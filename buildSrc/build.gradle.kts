plugins {
    `kotlin-dsl` // this will create our Gradle convention plugins

    // don't add the Kotlin JVM plugin
    // kotlin("jvm") version embeddedKotlinVersion
    // Why? It's a long story, but Gradle uses an embedded version of Kotlin,
    // (which is provided by the `kotlin-dsl` plugin)
    // which means importing an external version _might_ cause issues
    // It's annoying but not important. The Kotlin plugin version below,
    // in dependencies { }, will be used for building our 'main' project.
    // https://github.com/gradle/gradle/issues/16345
}

val kotlinVersion = "1.8.21"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
    implementation("com.github.johnrengelman:shadow:8.1.1")
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:1.8.10")
}