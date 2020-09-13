buildscript {
    val libraryRecommenderFile: File by extra {
        File(project.rootProject.projectDir, "libraries.txt")
    }

    val repos: RepositoryHandler.() -> Unit by extra {
        {
            jcenter()
            mavenCentral()

            maven("https://dl.bintray.com/kotlin/kotlin-eap")
            maven("https://kotlin.bintray.com/kotlinx")
            maven("https://kotlin.bintray.com/kotlin-js-wrappers")

//            maven("s3://steamstreet-repository/maven/release") {
//                authentication {
//                    val awsIm by registering(AwsImAuthentication::class)
//                }
//            }
        }
    }


    // bootstrap our version recommender by just loading properties directly
    val libraryVersions: Map<String, String> by extra {
        java.util.Properties().apply {
            load(object : java.io.FileReader(libraryRecommenderFile) {
                override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                    val pos = super.read(cbuf, off, len)
                    for (i in cbuf.indices)
                        if (cbuf[i] == ':')
                            cbuf[i] = '/'
                    return pos
                }
            })
        }.let { properties ->
            properties.toMap().map {
                val resolvedKey: String = (it.key as String).replace("/", ":")
                val resolvedValue: String = (it.value as String).let { v ->
                    if (v.startsWith("$")) {
                        properties[v.drop(1)] as String
                    } else v
                }

                resolvedKey to resolvedValue
            }.toMap()
        }
    }

    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libraryVersions["KOTLIN_VERSION"]}")
        classpath("org.jetbrains.kotlin:kotlin-serialization:${libraryVersions["KOTLIN_VERSION"]}")
    }

    repositories(repos)
}

allprojects {
    val repos: RepositoryHandler.() -> Unit by rootProject.extra
    repositories(repos)

    // Initialize library resolution so that all child projects pull from the master
    // version list.
    val libraryVersions: Map<String, String> by rootProject.extra
    this.project.configurations.all {
        resolutionStrategy {
            eachDependency {
                val version = libraryVersions["${this.requested.group}:${this.requested.name}"]
                if (version != null) {
                    this.useVersion(version)
                }
            }
        }
    }
}

subprojects {
    apply(plugin = "maven-publish")

    group = "com.steamstreet.graphkt"
    version = "0.1.0-${this.findProperty("BUILD_NUMBER")?.let { "build$it" } ?: "SNAPSHOT"}"

    this.tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "1.8"
        kotlinOptions.freeCompilerArgs = listOf("-Xuse-experimental=kotlin.Experimental")
    }

    configure<PublishingExtension> {
        repositories {
            maven {
                url = uri("s3://steamstreet-repository/maven/release")
                authentication {
                    val awsIm by registering(AwsImAuthentication::class)
                }
            }
        }
    }
}