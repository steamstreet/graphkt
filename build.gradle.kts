subprojects {
    group = "com.steamstreet.graphkt"
    version = "0.5.1-${this.findProperty("BUILD_NUMBER")?.let { "build$it" } ?: "SNAPSHOT"}"
}