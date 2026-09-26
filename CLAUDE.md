# GraphKt repository guide

## Project Overview

GraphKT is a GraphQL code generation and runtime framework for Kotlin Multiplatform. It generates type-safe GraphQL client and server code from schema files.

## Build Commands

```bash
# Build everything
./gradlew build

# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :client:test
./gradlew :code-generator:test

# Publish to Maven Local (for local testing)
./gradlew publishToMavenLocal

# Generate GraphQL code (in projects using the plugin)
./gradlew generateGraphQLCode
```

## Architecture

### Module Structure

| Module | Platform | Purpose |
|--------|----------|---------|
| `code-generator` | JVM | Core code generation engine using KotlinPoet and GraphQL-Java |
| `gradle-plugin` | JVM | Gradle plugin exposing `generateGraphQLCode` task |
| `common-runtime` | KMP | Shared utilities across platforms |
| `client` | KMP | GraphQL client interfaces (`GraphQLClient`, `QueryWriter`) |
| `client-ktor` | KMP | Ktor-based HTTP client implementation |
| `client-fetch` | JS | Browser Fetch API client |
| `client-direct` | KMP | In-process query, mutation, and subscription client |
| `server` | KMP | Common parser, validator, request scope, and execution engine |
| `server-ktor` | KMP | Ktor server route integration |
| `server-lambda` | JVM | AWS Lambda integration |

### Code Generation Flow

1. GraphQL schema is parsed by `code-generator` using GraphQL-Java
2. Generators produce Kotlin code via KotlinPoet:
   - `QueryGenerator` - Client query/mutation classes
   - `DataTypesGenerator` - Data type classes (inputs, types, enums)
   - `ServerInterfacesGenerator` - Server resolver interfaces
   - `ServerMappingGenerator` - Request-to-resolver mapping
   - `ResponseParserGenerator` - Client response parsing

3. Gradle plugin (`GraphQLGeneratorPlugin`) exposes this as a build task

### Using the Gradle Plugin

```kotlin
plugins {
    id("com.steamstreet.graphkt")
}

graphKt {
    schemaFiles.from("src/commonMain/graphql")
    packageName.set("com.example.graphql")
    client {
        enabled.set(true)
    }
    server {
        enabled.set(true)
    }
}
```

The plugin connects both generated output directories to `commonMain`. It also adds the generation task as a compilation dependency.

Generated client and server code supports Kotlin/Native. Client-only projects can disable server generation to reduce generated code.

## Key Technical Details

- **Kotlin version**: 2.3.0
- **Java toolchain**: 17
- **Context receivers**: Enabled via `-Xcontext-receivers`
- **Test framework**: JUnit 5
- **Multiplatform targets**:
  - Core runtime modules: JVM, JS, iOS, macOS, Linux, and Windows MinGW
  - `client-fetch`: JS only
  - `server-lambda`, generator, and Gradle plugin: JVM only
- **Published to**: Maven Central as `com.steamstreet.graphkt:<module>`, with `<module>-<target>` for each Kotlin target and the plugin as `com.steamstreet.graphkt:gradle-plugin`

## Convention Plugins

Two buildSrc convention plugins standardize module configuration:
- `graphkt.multiplatform-conventions` - For KMP modules
- `graphkt.jvm-conventions` - For JVM-only modules

Both apply serialization, publishing configuration, and Dokka documentation.

## Publishing coordinates

Every artifact publishes under the group `com.steamstreet.graphkt`, which the root build sets for all projects. Artifact IDs are the module names that Gradle and the Kotlin plugin assign: `server` for a module's metadata publication, `server-jvm` and `server-linuxarm64` for its targets. Do not rewrite `artifactId` anywhere. The Kotlin plugin assigns target names from its own `afterEvaluate`, so a rewrite applied in a publication block reaches the metadata publication and misses the targets. That split the awskt 3.0.0 namespace on Central, where it cannot be withdrawn.

The plugin ID `com.steamstreet.graphkt` gives the marker `com.steamstreet.graphkt:com.steamstreet.graphkt.gradle.plugin`, which points at `com.steamstreet.graphkt:gradle-plugin`. `gradle-plugin` is an ordinary subproject, so it publishes with the rest of the release.

Through 2.x the artifacts published as `com.steamstreet:graphkt-<module>`. Those coordinates remain on Central and are not maintained.

## Releasing

Run `scripts/release.sh` from a release branch such as `3.0.x`. Nebula derives the version from the latest tag, so a patch release is the default and `--scope minor` or `--scope major` overrides it. The script runs a clean `check` and publishes to a scratch repository. It refuses any coordinate outside `com.steamstreet.graphkt`. It then uploads, tags, publishes the deployment, and confirms that the artifacts answer on `repo1`. `--dry-run` stops after the coordinate check, before anything is uploaded or tagged.

Do not release with `./gradlew final` alone. It closes the staging repository but does not publish it. The deployment stops at `VALIDATED`, and `final` exits 0 having shipped nothing. Publish a stalled deployment at https://central.sonatype.com/publishing/deployments.

The script reads `mavenCentralUsername`, `mavenCentralPassword`, and the `signing.*` properties from `~/.gradle/gradle.properties`.

To inspect the publications without touching `~/.m2`, publish to a scratch repository:

```bash
./gradlew publishToMavenLocal -Dmaven.repo.local=/tmp/graphkt-m2
```
