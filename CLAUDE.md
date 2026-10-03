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
- **Published to**: the Steamstreet repository (`https://repo.steamstreet.com`) for every release, and Maven Central for some, as `com.steamstreet.graphkt:<module>`, with `<module>-<target>` for each Kotlin target and the plugin as `com.steamstreet.graphkt:gradle-plugin`

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

[docs/releasing.md](docs/releasing.md) describes the whole process. In short:

Run `scripts/release.sh` from a release branch such as `3.0.x`. By default it publishes to the Steamstreet repository only: the S3 bucket `steamstreet-repository`, served read-only at `https://repo.steamstreet.com`, where a release answers within a minute or two. `--target central` also publishes to Maven Central, which takes about two hours to reach `repo1`, for the occasional public release. Either target publishes every module, the Gradle plugin, and its marker to the Steamstreet repository, so it holds every version.

Releases are patches on the current line. Nebula derives the version from the latest tag, so a patch is the default. Do not pass `--scope minor` or `--scope major` unless the owner asks for one.

`--check` sets the verification before upload: `jvm` (the JVM tests; the default for the Steamstreet target), `full` (a clean `check`; the default for Central), or `none`. The coordinate check then compiles every target, so a native compile error still stops a release. `--dry-run` stops after the coordinate check, before anything is uploaded or tagged.

A Steamstreet release uploads, verifies every POM at `repo.steamstreet.com`, and only then tags and pushes. An interrupted run leaves no tag, and running it again finishes it. `--resume` publishes the version tagged at `HEAD` to the Steamstreet repository again, with no check and no tag, to finish a release whose tag went out early. It works only for 3.0.3 and later. Older tags predate the Steamstreet repository, so a release that is only on Maven Central is backfilled by copying Central's files, as `docs/releasing.md` describes. A release takes longer than 30 minutes, so run it from a shell or from a tool whose time limit is at least an hour.

Publishing to the Steamstreet repository uses the AWS profile `steamstreet-publisher` (or `GRAPHKT_PUBLISH_PROFILE`), a static key for the IAM user `steamstreet-maven-publisher`. The script gives it to Gradle only as `AWS_PROFILE`, and only for the publishing steps. Never export that key as `AWS_ACCESS_KEY_ID`. The bucket and the user are defined in awskt's `infrastructure/package-repository.yaml`. `-Pgraphkt.steamstreetRepositoryUrl=s3://steamstreet-repository/maven/<prefix>` publishes elsewhere in the bucket, which CloudFront does not serve, to try the publishing path without releasing. The publisher cannot delete, so remove a trial prefix with the owner's `steamstreet` profile.

Do not release with `./gradlew final` alone. With `-Pgraphkt.publishTarget=central`, it closes the staging repository but does not publish it. The deployment stops at `VALIDATED`, and `final` exits 0 having shipped nothing to Central. Publish a stalled deployment at https://central.sonatype.com/publishing/deployments. With the default target, `final` pushes its tag before the uploads finish.

The Central target reads `mavenCentralUsername`, `mavenCentralPassword`, and the `signing.*` properties from `~/.gradle/gradle.properties`.

To inspect the publications without touching `~/.m2`, publish to a scratch repository:

```bash
./gradlew publishToMavenLocal -Dmaven.repo.local=/tmp/graphkt-m2
```
