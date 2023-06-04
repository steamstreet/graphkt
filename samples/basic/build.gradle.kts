@file:Suppress("UNUSED_VARIABLE")

buildscript {
    val graphKtVersion: String by project

    repositories {
        mavenCentral()
        mavenLocal()
    }

    dependencies {
        classpath("com.steamstreet.graphkt:gradle-plugin:$graphKtVersion")
    }
}

val graphKtVersion: String by project

plugins {
    kotlin("multiplatform") version "1.8.21"
    kotlin("plugin.serialization") version "1.8.21"
}

apply(plugin = "com.steamstreet.graphkt")

repositories {
    mavenCentral()
    mavenLocal()
}

kotlin {
    jvm()
    js(IR) { browser() }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(File(project.buildDir, "graphql/generated"))

            dependencies {
                api("com.steamstreet.graphkt:client:$graphKtVersion")
                api("com.steamstreet.graphkt:server:$graphKtVersion")
                api("com.steamstreet.graphkt:common-runtime:$graphKtVersion")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("com.steamstreet.graphkt:client-ktor:$graphKtVersion")
                implementation("io.ktor:ktor-client-mock:2.3.0")
                implementation("org.amshove.kluent:kluent:1.73")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.4")
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation("com.steamstreet.graphkt:server-ktor:$graphKtVersion")
                api("com.graphql-java:graphql-java:20.3")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation("io.ktor:ktor-server-test-host:2.3.0")
                implementation("org.junit.jupiter:junit-jupiter-engine:5.5.2")
                implementation("io.mockk:mockk:1.13.5")
            }
        }
    }
}

tasks.all {
    if (this.name == "compileCommonMainKotlinMetadata") {
        this.dependsOn("generateGraphQLCode")
    }
}

configure<com.steamstreet.graphkt.generator.GraphQLExtension> {
    schema = File(projectDir, "schema.graphql").canonicalPath
    basePackage = "com.steamstreet.graphkt.samples.basic"
}