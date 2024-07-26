@file:Suppress("UnstableApiUsage")

plugins {
    id("graphkt.jvm-conventions")
    id("java-gradle-plugin")
}

dependencies {
    api(libs.graphql)
    api(libs.kotlin.poet)
    api(project(":code-generator"))
}

gradlePlugin {
    plugins {
        create("graphkt") {
            id = "com.steamstreet.graphkt"
            implementationClass = "com.steamstreet.graphkt.generator.GraphQLGeneratorPlugin"
        }
    }
}


publishing {
    publications {
        withType<MavenPublication> {
            pom {
                description.set("GraphKt Plugin")
            }
        }
    }
}

signing {
    sign(publishing.publications)
}

tasks.withType<Sign> {
    onlyIf { project.hasProperty("signing.keyId") }
}


val signingTasks = tasks.withType<Sign>()
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(signingTasks)
}