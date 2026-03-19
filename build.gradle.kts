plugins {
    id("io.github.gradle-nexus.publish-plugin") version "1.3.0"
    id("nebula.release") version "20.2.0"
}

allprojects {
    group = "com.steamstreet"
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))

            username = findProperty("mavenCentralUsername").toString()
            password = findProperty("mavenCentralPassword").toString()
        }
    }
}

// Create a task that will publish to Maven Local without running dokka tasks
subprojects {
    afterEvaluate {
        gradle.taskGraph.whenReady {
            if (gradle.taskGraph.hasTask(":${project.name}:publishToMavenLocal")) {
                tasks.matching { it.name in listOf("javadoc", "dokkaHtml", "dokkaJavadoc", "dokkaGeneratePublicationHtml") }.configureEach {
                    enabled = false
                }
            }
        }
    }
}

tasks.named("snapshot") {
    dependsOn(subprojects.flatMap { it.tasks.matching { it.name == "publishToMavenLocal" } })
}

val closeTask = tasks.named("closeAndReleaseSonatypeStagingRepository")
tasks.named("final") {
    dependsOn(subprojects.flatMap { it.tasks.matching { it.name == "publishToSonatype" } })
    dependsOn(closeTask)
}