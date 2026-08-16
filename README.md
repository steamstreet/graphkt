# GraphKt

GraphKt generates type-safe GraphQL clients and servers for Kotlin Multiplatform.

GraphKt 3.0 moves parsing, validation, coercion, execution, and generated server code into common Kotlin code. The build-time generator remains a JVM tool.

## Platforms

The core runtime supports these targets:

- JVM
- JavaScript IR
- iOS Arm64, X64, and Simulator Arm64
- macOS Arm64 and X64
- Linux Arm64 and X64
- Windows MinGW X64

The browser fetch adapter is JavaScript-only. The AWS Lambda adapter and build tools are JVM-only.

## Gradle setup

Apply the plugin and a Kotlin serialization plugin:

```kotlin
plugins {
    kotlin("multiplatform") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("com.steamstreet.graphkt") version "3.0.0"
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("com.steamstreet:graphkt-client-ktor:3.0.0")
        }
    }
}

graphKt {
    schemaFiles.from(file("src/commonMain/graphql"))
    packageName.set("com.example.graphql")
    server {
        enabled.set(false)
    }
}
```

The plugin connects generated code to `commonMain`. You do not need a manual source directory or task dependency.

## Documentation

- [Kotlin/Native guide](docs/native.md)
- [2.x-to-3.0 migration guide](docs/migration-2.x-to-3.0.md)
- [3.0 API reference](docs/api-reference.md)
- [Subscription guide](docs/subscriptions.md)
- [Performance baseline](docs/performance-baseline.md)
- [3.0.0 release notes](docs/release-notes-3.0.0.md)
- [3.0 implementation plan](docs/graphkt-3.0-plan.md)

## Samples

- [`samples/native-client`](samples/native-client) compiles the generated HTTP client for Kotlin/Native.
- [`samples/native-direct`](samples/native-direct) compiles the generated client and server APIs for JVM and Kotlin/Native.
- [`samples/basic`](samples/basic) contains broader client and server fixtures.

## Build

Run the complete repository checks:

```bash
./gradlew check
```

Publish a development snapshot to Maven Local before you run a standalone sample:

```bash
./gradlew snapshot
./gradlew -p samples/native-direct allTests
```

GraphKt uses the MIT license.
