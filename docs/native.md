# Kotlin/Native guide

GraphKt 3.0 supports generated clients and servers in common Kotlin code. The Gradle plugin and code generator run on the JVM during the build.

## Select an artifact

Use one of these runtime artifacts in `commonMain`:

| Artifact | Purpose |
|---|---|
| `graphkt-client-ktor` | Send query and mutation requests with a Ktor client engine. |
| `graphkt-client-direct` | Run queries, mutations, and subscriptions against an in-process server. |
| `graphkt-server` | Parse, validate, and execute requests without a transport. |
| `graphkt-server-ktor` | Add GET and POST GraphQL routes to a Ktor server. |
| `graphkt-common-runtime` | Use request, response, error, and optional-input models. |

The `graphkt-client-direct` artifact exports the client and server runtime dependencies.

## Configure the build

Add the GraphKt plugin to `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }

    plugins {
        id("com.steamstreet.graphkt") version "3.0.0"
    }
}
```

Configure the Kotlin targets and GraphKt in `build.gradle.kts`:

```kotlin
plugins {
    kotlin("multiplatform") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("com.steamstreet.graphkt")
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("com.steamstreet:graphkt-client-direct:3.0.0")
        }
    }
}

graphKt {
    schemaFiles.from(file("src/commonMain/graphql"))
    packageName.set("com.example.graphql")
}
```

Do not add generated source directories. The plugin connects both generated directories to `commonMain`.

## Create a common server

For this schema:

```graphql
schema {
    query: Query
}

type Query {
    greeting(name: String!): String!
}
```

Implement the generated resolver in common code:

```kotlin
import com.example.graphql.server.Query

class QueryResolver : Query {
    override suspend fun greeting(name: String): String = "Hello, $name"
}
```

Then create the generated server and a direct client:

```kotlin
import com.example.graphql.client.query
import com.example.graphql.server.graphKtServer
import com.steamstreet.graphkt.client.direct.GraphQLDirectClient
import com.steamstreet.graphkt.server.ResolverFactory

val server = graphKtServer(
    query = ResolverFactory { QueryResolver() },
)
val client = GraphQLDirectClient(server, Unit)

val response = client.query(name = "NativeGreeting") {
    greeting(name = "Native")
}
```

The server creates one resolver tree for each valid request. The direct client does not use GraphQL Java or JVM reflection.

## Use an HTTP client

Use `GraphQLKtorClient` with a Ktor engine that supports the selected Native target:

```kotlin
val client = GraphQLKtorClient(
    endpoint = "https://api.example.com/graphql",
    engine = nativeHttpEngine,
    headerInitializer = {
        mapOf("Authorization" to "Bearer $accessToken")
    },
)
```

The Ktor client supports query and mutation operations. It does not select a remote subscription protocol.

## Compile the samples

Publish the current GraphKt snapshot to Maven Local:

```bash
./gradlew snapshot
```

Compile and run the host-compatible Native tests:

```bash
./gradlew -p samples/native-client allTests
./gradlew -p samples/native-direct allTests
```

Cross-compiled targets can compile without running on the current host. Use a target-specific test-binary task for that check.

## Native constraints

- Run the generator through Gradle on a JVM host.
- Put generated runtime code in `commonMain`.
- If constructors differ, keep platform HTTP engines and server engines in platform source sets.
- Use `GraphQLDirectClient` for common subscription tests.
- Use `server-lambda` only in JVM projects.
- Use `client-fetch` only in JavaScript browser projects.
