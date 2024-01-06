subprojects {
    group = "com.steamstreet.graphkt"

    val releaseName = findProperty("RELEASE_NAME") as? String
    version = releaseName?.removePrefix("v") ?: "0.5.1-${this.findProperty("BUILD_NUMBER")?.let { "build$it" } ?: "SNAPSHOT"}"
}