plugins {
    id("graphkt.jvm-conventions")
}

dependencies {
    implementation(libs.kotlin.serialization.core)

    api(project(":common-runtime"))
    api(project(":server"))

    implementation(libs.graphql)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.aws.lambda.events)
}


kotlin {
    explicitApi()
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