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
        create("steamql") {
            id = "com.steamstreet.graphkt"
            implementationClass = "com.steamstreet.graphkt.generator.GraphQLGeneratorPlugin"
        }
    }
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

publishing {
    publications {
        create<MavenPublication>("pluginMaven") {
            artifact(sourcesJar.get())
        }
    }

    afterEvaluate {
        publications.forEach {
            (it as? MavenPublication)?.let {
                it.versionMapping {
                    allVariants {
                        fromResolutionResult()
                    }
                }
            }
        }
    }
}