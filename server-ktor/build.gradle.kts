plugins {
    kotlin("jvm")
    id("kotlinx-serialization")
    id("maven-publish")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core")

    implementation("ch.qos.logback:logback-classic")

    api(project(":common-runtime"))
    api(project(":server"))

    implementation("io.ktor:ktor-server-host-common")
    implementation("io.ktor:ktor-server-core")

    implementation("com.graphql-java:graphql-java")

    testImplementation("com.jayway.jsonpath:json-path")
    testImplementation("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlin:kotlin-test-annotations-common")
}


val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

publishing {
    publications {
        register("mavenJava", MavenPublication::class) {
            from(components["java"])
            artifact(sourcesJar.get())
        }
    }

    afterEvaluate {
        publications.forEach {
            (it as? MavenPublication)?.let {
                it.versionMapping {
                    allVariants {
                        fromResolutionResult()
                    }
                }

            }
        }
    }
}