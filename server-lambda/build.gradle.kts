plugins {
    id("graphkt.jvm-conventions")
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

    explicitApi()
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}


publishing {
    publications {
        withType<MavenPublication> {
            artifactId = "graphkt-${artifactId}"
            pom {
                description.set("GraphKt server implementation for the Lambda API Gateway proxy.")
            }
        }
    }
}