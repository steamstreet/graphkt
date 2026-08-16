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
- **Published to**: Maven Central as `com.steamstreet:graphkt-*`

## Convention Plugins

Two buildSrc convention plugins standardize module configuration:
- `graphkt.multiplatform-conventions` - For KMP modules
- `graphkt.jvm-conventions` - For JVM-only modules

Both apply serialization, publishing configuration, and Dokka documentation.
