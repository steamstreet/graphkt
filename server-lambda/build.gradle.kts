plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `maven-publish`
}

dependencies {
    implementation(libs.kotlin.serialization.core)

    api(project(":common-runtime"))
    api(project(":server"))

    implementation(libs.graphql)
    implementation(libs.kotlin.coroutines)
    implementation(libs.aws.lambda.events)
}

kotlin {
    jvmToolchain(11)
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