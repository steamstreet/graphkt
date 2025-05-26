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
            displayName = "GraphKt Plugin"
            description = "Plugin for generating GraphQL code from a GraphQL schema"
        }
    }
}


publishing {
    publications {
        withType<MavenPublication> {
            artifactId = "graphkt-${artifactId}"
            pom {
                name.set("GraphKT: ${project.name}")
                url.set("https://github.com/steamstreet/graphkt")
                description.set("GraphKt Plugin")

                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        organization.set("SteamStreet LLC")
                        organizationUrl.set("https://github.com/steamstreet")
                    }
                }
                scm {
                    url.set("https://github.com/steamstreet/graphkt")
                }
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