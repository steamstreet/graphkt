plugins {
    id("graphkt.multiplatform-conventions")
    id("org.danilopianini.publish-on-central")
}

kotlin {
    jvm()
    js(IR) { browser() }

    iosArm64()
    iosX64()
    iosSimulatorArm64()

    explicitApi()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.serialization.json)
            }
        }
    }
}

publishOnCentral {
    projectDescription.set("Common runtime used for clients and servers of GraphKt")
    projectLongName.set("GraphKt Common Runtime")
    licenseName.set("MIT License")
    licenseUrl.set("https://opensource.org/licenses/MIT")
    projectUrl.set("https://github.com/steamstreet/graphkt")
    scmConnection.set("git:git@github.com:steamstreet/graphkt")
}

//
//publishing {
//    publications {
//        withType<MavenPublication> {
//            artifactId = "graphkt-${artifactId}"
//            pom {
//                description.set("Common runtime used for clients and servers of GraphKt")
//            }
//        }
//    }
//}