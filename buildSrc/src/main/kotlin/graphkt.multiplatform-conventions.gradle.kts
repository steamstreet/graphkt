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
        // Group and artifactId are inherited: `com.steamstreet.graphkt` from the root project, and the
        // Kotlin plugin's own names — `<module>` for the metadata publication and `<module>-<target>`
        // for each target. Nothing may rewrite them here. The Kotlin plugin assigns the target names
        // from its own `afterEvaluate`, so a rewrite applied in this block reaches the metadata
        // publication and misses the targets, which is how awskt 3.0.0 shipped a split namespace.
        artifact(tasks.findByName("javadocJar"))

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