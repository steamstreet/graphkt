@file:Suppress("UnstableApiUsage")

plugins {
    kotlin("jvm")
    id("java-gradle-plugin")
    maven
}

dependencies {
    api("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    api("org.jetbrains.kotlin:kotlin-reflect")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8")

    api("com.graphql-java:graphql-java")
    api("com.squareup:kotlinpoet")
}

gradlePlugin {
    plugins {
        create("steamql") {
            id = "com.steamstreet.steamql"
            implementationClass = "com.steamstreet.steamql.generator.GraphQLGeneratorPlugin"
        }
    }
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

publishing {
    publications {
        register("mavenJava", MavenPublication::class) {
            from(components["java"])
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