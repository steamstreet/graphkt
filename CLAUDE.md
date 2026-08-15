# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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
| `client-direct` | JVM | Direct client using GraphQL-Java |
| `server` | JVM/JS | Server-side request handling |
| `server-ktor` | JVM | Ktor server integration |
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

GraphQL {
    schema = "src/main/graphql/schema.graphqls"
    basePackage = "com.example.graphql"
    generateClient = true   // default: true
    generateServer = true   // default: true
}
```

The plugin wires `generateGraphQLCode` ahead of every Kotlin compilation task (per-target compiles and KMP
metadata compiles), so consumers only need to add `build/graphql/generated` as a source directory.

Native (Kotlin/Native) consumers must set `generateServer = false`: the generated server code depends on the
`server` module, which is JVM/JS only, and it is written to the same output directory as the client code.

## Key Technical Details

- **Kotlin version**: 2.3.0
- **Java toolchain**: 17
- **Context receivers**: Enabled via `-Xcontext-receivers`
- **Test framework**: JUnit 5
- **Multiplatform targets**:
  - `common-runtime`, `client`, `client-ktor`: JVM, JS, iOS (Arm64, X64, SimulatorArm64), macOS (X64, Arm64), Linux (X64, Arm64), Windows (mingwX64)
  - `server`: JVM, JS
  - Everything else: JVM only
- **Published to**: Maven Central as `com.steamstreet:graphkt-*`

## Convention Plugins

Two buildSrc convention plugins standardize module configuration:
- `graphkt.multiplatform-conventions` - For KMP modules
- `graphkt.jvm-conventions` - For JVM-only modules

Both apply serialization, publishing configuration, and Dokka documentation.