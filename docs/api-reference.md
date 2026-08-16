# GraphKt 3.0 API reference

This reference describes the stable public concepts for the GraphKt 3.0 line. Generated type names depend on the configured schema and package.

## Artifact and platform matrix

| Artifact | Platforms | Public purpose |
|---|---|---|
| `graphkt-common-runtime` | JVM, JavaScript, Apple, Linux, MinGW | Request, response, error, ID, and optional-input models. |
| `graphkt-client` | JVM, JavaScript, Apple, Linux, MinGW | One-shot and subscription client contracts. |
| `graphkt-client-ktor` | JVM, JavaScript, Apple, Linux, MinGW | Ktor query and mutation transport. |
| `graphkt-client-direct` | JVM, JavaScript, Apple, Linux, MinGW | In-process query, mutation, and subscription transport. |
| `graphkt-client-fetch` | JavaScript | Browser query and mutation transport. |
| `graphkt-server` | JVM, JavaScript, Apple, Linux, MinGW | Parser, validator, request scope, and execution engine. |
| `graphkt-server-ktor` | JVM, JavaScript, Apple, Linux, MinGW | Ktor GET and POST route adapter. |
| `graphkt-server-lambda` | JVM | API Gateway v2 HTTP adapter. |
| `graphkt-code-generator` | JVM | Programmatic schema generator. |
| `graphkt-gradle-plugin` | JVM | Gradle generation and source-set integration. |

Apple includes the configured iOS and macOS targets. Linux includes X64 and Arm64. MinGW uses X64.

## Gradle plugin

Apply plugin ID `com.steamstreet.graphkt`.

```kotlin
graphKt {
    schemaFiles.from(file("src/commonMain/graphql"))
    propertiesFile.set(file("src/commonMain/graphql/schema.properties"))
    packageName.set("com.example.graphql")
    client {
        enabled.set(true)
    }
    server {
        enabled.set(true)
    }
}
```

`schemaFiles` accepts files and directories. The task reads `.graphql` files from configured directories.

`propertiesFile` configures custom scalar types and serializers:

```properties
scalar.Instant.class=kotlinx.datetime.Instant
scalar.Instant.serializer=kotlinx.datetime.InstantIso8601Serializer
```

The plugin connects generated code to KMP `commonMain` or Kotlin/JVM `main`. The `generateGraphQLCode` task supports incremental and cached builds.

The generated directories are:

- `build/graphql/generated` for common models and client code.
- `build/graphql/server/generated` for server interfaces, metadata, and mapping code.

## Common request models

`GraphQLRequest` contains these members:

```kotlin
data class GraphQLRequest(
    val query: String,
    val operationName: String? = null,
    val variables: JsonObject? = null,
    val extensions: JsonObject? = null,
)
```

`GraphQLResponseEnvelope` contains `data`, `errors`, `extensions`, and an internal phase marker named `kind`.

The serializer preserves the difference between an absent `data` member and an explicit `data: null` member.

`GraphQLError.path` contains `GraphQLPathSegment.Field` and `GraphQLPathSegment.Index` values.

## Optional input values

`OptionalInput` preserves GraphQL omission:

```kotlin
sealed interface OptionalInput<out Value> {
    data object Absent : OptionalInput<Nothing>
    data class Present<Value>(val value: Value) : OptionalInput<Value>
}
```

Use `value.asOptionalInput()` to create `OptionalInput.Present(value)`.

Generated client parameters use `OptionalInput` for nullable arguments and arguments with schema defaults. Other parameters use their direct Kotlin types.

## Generated client API

Each schema operation root produces one extension function:

```kotlin
suspend fun GraphQLClient.query(
    name: String? = null,
    block: _QueryQuery.() -> Unit,
): Query

suspend fun GraphQLClient.mutation(
    name: String? = null,
    block: _MutationQuery.() -> Unit,
): Mutation

fun GraphQLSubscriptionClient.subscription(
    name: String? = null,
    block: _SubscriptionQuery.() -> Unit,
): Flow<Subscription>
```

The actual response type names match the schema root names. GraphKt preserves custom operation-root names.

Use the optional `name` argument for logs, server operation selection, and transport caches.

`GraphQLClient` defines one-shot execution. `GraphQLSubscriptionClient` defines streaming execution.

## Generated server API

GraphKt generates one resolver interface for each object, interface, and union. Resolver methods are suspend functions with schema arguments only.

```kotlin
interface Query {
    suspend fun user(id: ID): User?
}

interface User {
    suspend fun id(): ID
    suspend fun name(): String
}
```

A subscription-root resolver method returns `Flow<T>`:

```kotlin
interface Subscription {
    suspend fun userChanged(id: ID): Flow<User>
}
```

Generated resolvers do not receive a context parameter. Store request state in the request-scoped resolver objects.

## Server construction

The generated `graphKtServer` function connects generated schema metadata and resolvers to the common executor:

```kotlin
val server = graphKtServer(
    query = ResolverFactory { context -> QueryResolver(context) },
    mutation = ResolverFactory { context -> MutationResolver(context) },
    subscription = ResolverFactory { context -> SubscriptionResolver(context) },
    limits = GraphQLDocumentLimits(),
    executionPolicy = GraphQLExecutionPolicy(),
    directiveHandlers = emptyMap(),
    errorMapper = GraphQLExecutionErrorMapper { _, _, path ->
        GraphQLError(message = "Internal Server Error", path = path)
    },
)
```

Only `query` is always present. The generator adds `mutation` and `subscription` parameters when the schema defines those roots.

`ResolverFactory` creates one lightweight root resolver for each valid operation. Parsing, validation, and coercion finish before resolver construction.

## Server execution

Use `GraphQLServer.execute` for a query or mutation:

```kotlin
val response = server.execute(request) {
    createRequestContext()
}
```

If a transport permits only query operations, use `GraphQLServer.executeQuery`. It rejects mutation and subscription operations.

Use `GraphQLServer.subscribe` for subscriptions:

```kotlin
val responses: Flow<GraphQLResponseEnvelope> = server.subscribe(request) {
    createRequestContext()
}
```

The returned flow is cold. Collection starts request preparation and creates one context and resolver tree.

## Request scope and batching

The context factory has a `GraphQLRequestScope` receiver. It owns cleanup actions and request batch loaders.

```kotlin
val usersById = batchLoader(
    BatchLoader { ids -> users.find(ids).associateBy(UserRecord::id) },
)

onClose {
    transaction.close()
}
```

`RequestBatchLoader` provides these suspend functions:

- `load(key)`
- `loadMany(keys)`
- `clear(key)`
- `clearAll()`
- `prime(key, value)`

The loader combines unique keys from concurrent fields. Its cache and cleanup actions end with the request.

## Execution policy

`GraphQLExecutionPolicy.maximumQueryParallelism` sets the maximum concurrent query-root fields. Its default value is 16.

Mutation-root fields run serially in document order. Subscription operations select one root response key.

Cancellation passes through the executor. GraphKt does not convert cancellation into a GraphQL error.

## Document limits

`GraphQLDocumentLimits` applies before resolver construction:

| Property | Default |
|---|---:|
| `maxDocumentBytes` | 1,048,576 |
| `maxTokens` | 50,000 |
| `maxSyntaxNesting` | 128 |
| `maxSelectionDepth` | 64 |
| `maxSelectedFields` | 10,000 |
| `maxAliases` | 1,000 |
| `maxFragmentExpansions` | 10,000 |
| `maxFragmentNesting` | 128 |
| `maxVariableBytes` | 1,048,576 |

If the application accepts small operations, set lower values. All size and nesting values must be finite.

## Error mapping

Unexpected resolver failures map to `Internal Server Error` by default. The default mapper includes the response path and excludes internal failure text.

`GraphQLExecutionErrorMapper` can map known application failures. The application remains responsible for server-side logging.

Resource cleanup failures throw `GraphQLRequestCleanupException`. Its `failures` member contains all cleanup failures.

## Field directives

Register handlers by schema directive name:

```kotlin
val handlers = mapOf(
    "cached" to GraphQLDirectiveHandler<RequestContext> { context, directive, selection, next ->
        context.cache.resolve(selection.path, directive.arguments) {
            next.proceed()
        }
    },
)
```

A handler can read coerced directive arguments. It can call the continuation once.

GraphKt accepts handlers only for directives that include the GraphQL `FIELD` location.

## Ktor client

Create `GraphQLKtorClient` with an endpoint, an optional engine, and an optional header factory:

```kotlin
val client = GraphQLKtorClient(
    endpoint = "https://api.example.com/graphql",
    engine = engine,
    headerInitializer = { mapOf("Authorization" to token) },
)
```

Queries use GET. Mutations use POST with an `application/json` request envelope.

The client sends `operationName` when generated code supplies a name. It accepts `application/graphql-response+json` and `application/json`.

## Ktor server

Install GET and POST routes on a `Route`:

```kotlin
route("/graphql") {
    graphQL(server) { call ->
        createRequestContext(call)
    }
}
```

If the context type is `Unit`, use `graphQL(server)`.

The Ktor server adapter compiles for the same platforms as the common server. The application supplies a compatible Ktor server engine.

## Direct client

`GraphQLDirectClient` implements both client contracts:

```kotlin
val client = GraphQLDirectClient(server) {
    createRequestContext()
}
```

If each operation uses one fixed context value, use the second constructor:

```kotlin
val client = GraphQLDirectClient(server, Unit)
```

## Lambda adapter

Create a handler from a common server:

```kotlin
val handler = GraphQLLambda(server) { event ->
    createRequestContext(event)
}
```

The Lambda adapter supports API Gateway v2 HTTP GET and POST events. It supports plain and base64-encoded POST bodies.

## HTTP status and media rules

| Result | Status |
|---|---:|
| Malformed JSON or GraphQL document | 400 |
| Invalid envelope, validation, operation selection, or variables | 422 |
| Executed result, with or without field errors | 200 |
| No acceptable response media type | 406 |
| Unsupported POST content type | 415 |
| Unsupported method or mutation through GET | 405 |
| Transport failure before a GraphQL response | 500 |

POST requests require UTF-8 `application/json`. Responses negotiate `application/graphql-response+json` and `application/json`.

## Subscription transport boundary

GraphKt 3.0 defines common subscription flows. It does not select a WebSocket or Server-Sent Events protocol.

The direct client supports subscriptions. HTTP clients and the provided HTTP server adapters support one-shot operations only.
