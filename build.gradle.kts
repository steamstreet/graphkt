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
    // First, upload all publications from all subprojects
    subprojects.forEach { subproject ->
        subproject.plugins.withId("org.danilopianini.publish-on-central") {
            dependsOn("${subproject.path}:publishAllPublicationsToProjectLocalRepository")
        }
    }

    // Then zip and release once at the root
    dependsOn(
        "zipMavenCentralPortalPublication",
        "releaseMavenCentralPortalPublication"
    )
}