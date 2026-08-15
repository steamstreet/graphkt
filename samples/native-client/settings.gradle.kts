rootProject.name = "native-client"

pluginManagement {
    val graphKtVersion: String by settings

    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }

    plugins {
        id("com.steamstreet.graphkt") version graphKtVersion
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}
