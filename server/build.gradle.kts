plugins {
    kotlin("multiplatform")
    id("kotlinx-serialization")
    id("maven-publish")
}

kotlin {
    jvm()
    js { browser() }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime-common:0.20.0")

                api(project(":common-runtime"))
            }
        }

        val jvmMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-serialization-runtime:0.20.0")
            }
        }
        val jsMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-serialization-runtime-js:0.20.0")
            }
        }
    }
}

//
//dependencies {
//    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
//    implementation("org.jetbrains.kotlin:kotlin-reflect")
//    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8")
//    implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime")
//
//    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
//    implementation("ch.qos.logback:logback-classic")
//
//    api(project(":common-runtime"))
//}

//
//val sourcesJar by tasks.registering(Jar::class) {
//    archiveClassifier.set("sources")
//    from(sourceSets.main.get().allSource)
//}
//
//publishing {
//    publications {
//        register("mavenJava", MavenPublication::class) {
//            from(components["java"])
//            artifact(sourcesJar.get())
//        }
//    }
//
//    afterEvaluate {
//        publications.forEach {
//            (it as? MavenPublication)?.let {
//                it.versionMapping {
//                    allVariants {
//                        fromResolutionResult()
//                    }
//                }
//
//            }
//        }
//    }
//}