plugins {
    id("io.github.gradle-nexus.publish-plugin") version "1.3.0"
    id("nebula.release") version "20.2.0"
}

allprojects {
    // The project owns the `com.steamstreet.graphkt` namespace, so the group carries the project name
    // and the artifacts do not have to: `com.steamstreet.graphkt:server`, `:server-jvm`, and so on.
    // Through 2.x these published as `com.steamstreet:graphkt-<module>`, which needed a prefix forced
    // onto every artifactId. Central coordinates are immutable, so the 2.x coordinates remain
    // published and are not maintained.
    group = "com.steamstreet.graphkt"
}

nexusPublishing {
    // The default client timeout is five minutes, which is not enough. Closing a staging repository
    // that holds every module's artifacts took 272 seconds for awskt, which releases the same way, so
    // the default sits close enough to the real duration to time out and fail the release *after*
    // everything has been uploaded, leaving the release untagged.
    clientTimeout.set(java.time.Duration.ofMinutes(30))
    connectTimeout.set(java.time.Duration.ofMinutes(5))

    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))

            // Left null when the properties are absent so that the publishing tasks report missing
            // credentials. Reading them with toString() would send the literal string "null" and
            // surface as an authentication failure instead.
            username = findProperty("mavenCentralUsername") as String?
            password = findProperty("mavenCentralPassword") as String?
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

// Only close the staging repository, because `closeAndReleaseSonatypeStagingRepository` waits for a
// 'released' state that the Central Portal's OSSRH compatibility API never reports. Releasing that
// way fails *after* the artifacts have been uploaded, which leaves the release untagged and makes the
// next build reuse the version.
//
// IMPORTANT: closing is not publishing, and `final` therefore does not finish a release. The
// deployment stops at VALIDATED and stays there until something publishes it explicitly. `final`
// exits 0 either way, so a release driven by it alone looks like it succeeded and ships nothing.
//
// Use `scripts/release.sh`, which runs `final`, then publishes the deployment and verifies that the
// artifacts answer on repo1. To publish by hand instead, POST to
// https://central.sonatype.com/api/v1/publisher/deployment/<id>, or click Publish at
// https://central.sonatype.com/publishing/deployments.
val closeTask = tasks.named("closeSonatypeStagingRepository")
tasks.named("final") {
    dependsOn(subprojects.flatMap { it.tasks.matching { it.name == "publishToSonatype" } })
    dependsOn(closeTask)
}