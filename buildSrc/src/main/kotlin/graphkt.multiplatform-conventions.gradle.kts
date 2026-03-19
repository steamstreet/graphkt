plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    `maven-publish`
    id("org.jetbrains.dokka")
    signing
}

tasks.withType<Test> {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

val dokkaGeneratePublicationHtml by tasks.getting
val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    dependsOn(dokkaGeneratePublicationHtml)
    archiveClassifier.set("javadoc")
    from(dokkaGeneratePublicationHtml.outputs)
}

publishing {
    publications.withType<MavenPublication> {
        artifact(tasks.findByName("javadocJar"))
        groupId = "com.steamstreet"

        pom {
            name.set("GraphKT: ${project.name}")
            description.set(project.description)
            url.set("https://github.com/steamstreet/graphkt")

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