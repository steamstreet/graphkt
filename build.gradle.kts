plugins {
    id("nebula.release") version "20.2.0"
    id ("org.danilopianini.publish-on-central") version "8.0.7"
}

allprojects {
    group = "com.steamstreet"
}

//nexusPublishing {
//    repositories {
//        sonatype {
//            username = findProperty("sonatypeUsername").toString()
//            password = findProperty("sonatypePassword").toString()
//        }
//    }
//}
//
//tasks.named("snapshot") {
//    dependsOn(subprojects.flatMap { it.tasks.matching { it.name == "publishToMavenLocal" } })
//}
//
//val closeTask = tasks.named("closeAndReleaseSonatypeStagingRepository")
//tasks.named("final") {
//    dependsOn(subprojects.flatMap { it.tasks.matching { it.name == "publishToSonatype" } })
//    dependsOn(closeTask)
//}

tasks.named("postRelease") {
    dependsOn(
        "publishAllPublicationsToProjectLocalRepository",
        "zipMavenCentralPortalPublication",
        "releaseMavenCentralPortalPublication"
    )
}

tasks {
    // Prevent publishing the root project (since is empty)
    withType<AbstractPublishToMaven>().configureEach {
        enabled = false
    }
    withType<GenerateModuleMetadata>().configureEach {
        enabled = false
    }
}