buildscript {
    repositories {
        mavenLocal()
        jcenter()
    }

    dependencies {
        classpath("com.steamstreet:steamql-plugin:1.0.10")
        classpath("org.jetbrains.kotlin:kotlin-serialization:1.3.72")
    }
}

plugins {
    kotlin("multiplatform") version "1.3.72"
    kotlin("plugin.serialization") version "1.3.72"
}

apply(plugin = "com.steamstreet.steamql")

repositories {
    mavenLocal()
    jcenter()
}

kotlin {
    jvm()
    js { browser() }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(File(project.buildDir, "graphql/generated"))

            dependencies {
                implementation(kotlin("stdlib-common"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime-common:0.20.0")

                api("com.steamstreet:steamql-client:1.0.10")
                api("com.steamstreet:steamql-runtime:1.0.10")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation("org.amshove.kluent:kluent-common:1.61")
            }
        }

        val jvmMain by getting {
            kotlin.srcDir(File(project.buildDir, "graphql/server/generated"))

            dependencies {
                implementation(kotlin("stdlib-jdk8"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime:0.20.0")
                implementation("com.steamstreet:steamql-client-jvm:1.0.10")
                implementation("com.steamstreet:steamql-server-ktor:1.0.10")

                api("com.graphql-java:graphql-java:2019-11-07T04-06-09-70d9412")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation("org.amshove.kluent:kluent:1.61")
                implementation(kotlin("test-junit5"))
                implementation("org.junit.jupiter:junit-jupiter-engine:5.5.2")
            }
        }

        val jsMain by getting {
            dependencies {
                implementation(kotlin("stdlib-js"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime-js:0.20.0")
                implementation("com.steamstreet:steamql-client-js:1.0.10")
            }
        }

        val jsTest by getting {
            dependencies {
                implementation("org.amshove.kluent:kluent-js:1.61")
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks["jsBrowserWebpack"].enabled = false
tasks["jsBrowserProductionWebpack"].enabled = false

configure<com.steamstreet.steamql.generator.GraphQLExtension> {
    schema = File(projectDir, "schema.graphql").canonicalPath
    basePackage = "com.steamstreet.steamql.samples.basic"
}