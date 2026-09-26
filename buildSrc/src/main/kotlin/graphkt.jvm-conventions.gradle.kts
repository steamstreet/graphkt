plugins {
    kotlin("jvm")
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
    withSourcesJar()
}


val dokkaGeneratePublicationHtml by tasks.getting
val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    dependsOn(dokkaGeneratePublicationHtml)
    archiveClassifier.set("javadoc")
    from(dokkaGeneratePublicationHtml.outputs)
}

publishing {
    // `java-gradle-plugin` creates its own `pluginMaven` publication from the `java` component. A
    // second one here would publish the same coordinates twice, so it is only created for plain
    // libraries. This runs after evaluation so that it does not depend on the order in which a build
    // script applies the two plugins.
    afterEvaluate {
        if (!pluginManager.hasPlugin("java-gradle-plugin")) {
            publications.create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
    }

    // Group and artifactId are inherited: `com.steamstreet.graphkt` from the root project and the
    // module's own name.
    publications.withType<MavenPublication>().configureEach {
        // A plugin marker publication carries only a POM that points at the plugin's implementation.
        if (!name.endsWith("PluginMarkerMaven")) {
            artifact(javadocJar)
        }

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
