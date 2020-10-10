buildscript {
    val graphKtVersion: String by project

    repositories {
        mavenLocal()
        jcenter()
    }

    dependencies {
        classpath("com.steamstreet.graphkt:gradle-plugin:$graphKtVersion")
    }
}

val graphKtVersion: String by project

plugins {
    kotlin("multiplatform") version "1.4.10"
    kotlin("plugin.serialization") version "1.4.10"
}

apply(plugin = "com.steamstreet.graphkt")

repositories {
    mavenLocal()
    jcenter()
}

kotlin {
    jvm()

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(File(project.buildDir, "graphql/generated"))

            dependencies {
                api("com.steamstreet.graphkt:client:$graphKtVersion")
                api("com.steamstreet.graphkt:server:$graphKtVersion")
                api("com.steamstreet.graphkt:common-runtime:$graphKtVersion")
            }
        }

        val jvmMain by getting {
            kotlin.srcDir(File(project.buildDir, "graphql/server/generated"))

            dependencies {
                implementation("com.steamstreet.graphkt:server-ktor:$graphKtVersion")
                implementation("io.ktor:ktor-server-netty:1.4.0")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation("org.amshove.kluent:kluent:1.61")
                implementation(kotlin("test-junit5"))
                implementation("org.junit.jupiter:junit-jupiter-engine:5.5.2")
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

configure<com.steamstreet.graphkt.generator.GraphQLExtension> {
    schema = File(projectDir, "schema.graphql").canonicalPath
    basePackage = "com.steamstreet.graphkt.samples.server"
}