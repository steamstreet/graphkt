# Migrate from GraphKt 2.x to 3.0

GraphKt 3.0 changes generated server APIs, request execution, Gradle configuration, and error paths. It does not provide source or binary compatibility with 2.x.

Migrate generated code and application code in one change. You can use a temporary package name to run both versions during a staged migration.

## 1. Update the build

Replace the eager 2.x extension:

```kotlin
GraphQL {
    schema = file("schema.graphql").absolutePath
    basePackage = "com.example.graphql"
    generateClient = true
    generateServer = true
}
```

Use the lazy 3.0 extension:

```kotlin
graphKt {
    schemaFiles.from(file("schema.graphql"))
    packageName.set("com.example.graphql")
    client {
        enabled.set(true)
    }
    server {
        enabled.set(true)
    }
}
```

The old `GraphQL` extension and its four properties remain as deprecated aliases. Remove them before the end of the 3.0 migration.

Remove manual source directories such as these entries:

```kotlin
kotlin.srcDir(layout.buildDirectory.dir("graphql/generated"))
kotlin.srcDir(layout.buildDirectory.dir("graphql/server/generated"))
```

Also remove manual dependencies on `generateGraphQLCode`. The plugin connects both directories and the generation task to each Kotlin compilation.

For multiple schemas, add files or a directory to `schemaFiles`. GraphKt merges all `.graphql` files in a configured directory.

## 2. Update runtime dependencies

If the application has Kotlin Multiplatform targets, use runtime artifacts in `commonMain`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.steamstreet:graphkt-client-ktor:3.0.0")
            implementation("com.steamstreet:graphkt-server:3.0.0")
        }
    }
}
```

The common server runtime no longer needs GraphQL Java. Do not add GraphQL Java for generated 3.0 server code.

The generator and Gradle plugin remain JVM build tools. This constraint does not limit the targets of generated code.

## 3. Recreate generated code

Delete no generated directories by hand. Run the generation task:

```bash
./gradlew generateGraphQLCode
```

The task stages new output before it replaces old output. Disabled client or server output becomes empty during a successful run.

## 4. Replace server route callbacks

GraphKt 2.x routes receive a generated selection callback:

```kotlin
route("/graphql") {
    graphQL(
        query = QueryResolver()::gqlSelect,
        mutation = MutationResolver()::gqlSelect,
    )
}
```

GraphKt 3.0 routes receive one common `GraphQLServer`:

```kotlin
val server = graphKtServer(
    query = ResolverFactory { context -> QueryResolver(context) },
    mutation = ResolverFactory { context -> MutationResolver(context) },
)

route("/graphql") {
    graphQL(server) { call ->
        RequestContext(principal = authenticate(call))
    }
}
```

The generated `graphKtServer` function supplies schema metadata and mapping code. Do not call generated `gqlSelect` functions from application code.

## 5. Move request state into resolver construction

GraphKt 2.x can read request state through `gqlRequestContext()` and JVM thread state. GraphKt 3.0 removes that request-state model.

Pass application state through the context factory:

```kotlin
data class RequestContext(
    val principal: Principal,
    val users: UserService,
)

class QueryResolver(
    private val context: RequestContext,
) : Query {
    override suspend fun viewer(): User? =
        context.users.find(context.principal.userId)?.let(::UserResolver)
}
```

Generated resolver methods contain only schema arguments. They do not receive a `ResolverContext` or transport request.

The server constructs one root resolver tree for each valid operation. Small request-scoped resolver objects are the expected design.

## 6. Register request resources

The Ktor and Lambda context factories use `GraphQLRequestScope` as their receiver. Register resource cleanup in this scope:

```kotlin
graphQL(server) { call ->
    val transaction = database.openTransaction()
    onClose { transaction.close() }

    RequestContext(
        principal = authenticate(call),
        transaction = transaction,
    )
}
```

GraphKt runs cleanup actions once in reverse order. Cleanup also runs after cancellation, resolver failure, or context failure.

## 7. Replace application batching

Create request-scoped batch loaders in the context factory:

```kotlin
graphQL(server) { call ->
    RequestContext(
        principal = authenticate(call),
        usersById = batchLoader(
            BatchLoader { ids -> users.find(ids).associateBy(UserRecord::id) },
        ),
    )
}
```

Concurrent fields share the loader cache for one operation. A later operation receives a new cache.

## 8. Preserve omitted client arguments

Generated 3.0 clients use `OptionalInput` when omission has a different meaning from `null`.

Omit an argument:

```kotlin
client.query {
    search(term = OptionalInput.Absent) {
        id
    }
}
```

Send an explicit `null` value:

```kotlin
client.query {
    search(term = OptionalInput.Present(null)) {
        id
    }
}
```

Send a present value:

```kotlin
client.query {
    search(term = "kotlin".asOptionalInput()) {
        id
    }
}
```

Non-null arguments without schema defaults remain ordinary required Kotlin parameters.

## 9. Update error-path code

`GraphQLError.path` now has the type `List<GraphQLPathSegment>?`. A path can contain a field name or a list index.

```kotlin
when (val segment = error.path?.firstOrNull()) {
    is GraphQLPathSegment.Field -> useField(segment.name)
    is GraphQLPathSegment.Index -> useIndex(segment.index)
    null -> Unit
}
```

This model preserves integer indexes in GraphQL response envelopes. Do not convert all path entries to strings.

## 10. Configure public resolver errors

GraphKt returns `Internal Server Error` for unexpected resolver failures by default. It does not return exception messages or stack traces.

If the public response needs an application error, use an error mapper:

```kotlin
val server = graphKtServer(
    query = ResolverFactory(::QueryResolver),
    errorMapper = GraphQLExecutionErrorMapper { _, failure, path ->
        logResolverFailure(failure)
        GraphQLError(message = "Internal Server Error", path = path)
    },
)
```

Do not return secrets, database text, file paths, or stack traces from the mapper.

## 11. Update Ktor HTTP behavior

POST requests must use UTF-8 `application/json`. The Ktor client now sends a JSON request envelope.

The server negotiates `application/graphql-response+json` and `application/json`. Document errors use status 400, and request errors use status 422.

Execution results use status 200, including results that contain field errors. GET requests accept queries only.

If your tests expect status 200 for malformed requests, update those expectations.

## 12. Update Lambda construction

Replace the 2.x Lambda callback DSL:

```kotlin
val handler = GraphQLLambda {
    query { event, selection -> QueryResolver(event).gqlSelect(selection) }
}
```

Use the common server in 3.0:

```kotlin
val handler = GraphQLLambda(server) { event ->
    RequestContext(principal = authenticate(event))
}
```

The new handler accepts API Gateway v2 HTTP events. It applies the same preparation and HTTP rules as the Ktor adapter.

## 13. Add subscriptions

Generated subscription root fields return `Flow<T>`. The common server returns one response envelope for each source event.

Use `GraphQLDirectClient` for in-process subscriptions. For remote subscriptions, implement a selected WebSocket or Server-Sent Events protocol.

Read the [subscription guide](subscriptions.md) for lifecycle and validation rules.

## Compatibility summary

| Area | 2.x to 3.0 status |
|---|---|
| GraphQL schema files | Compatible unless the old generator accepted invalid schema behavior. |
| Generated Kotlin source | Regeneration required. |
| Generated resolver interfaces | Source-breaking. |
| `GraphQL` Gradle extension | Retained as a deprecated alias. |
| Manual generated-source wiring | Remove it. |
| `gqlRequestContext()` | Removed from the 3.0 execution model. |
| Ktor callback routes | JVM compatibility entry points remain. New code must use `GraphQLServer`. |
| Lambda callback DSL | JVM compatibility entry points remain. New code must use `GraphQLServer`. |
| `GraphQLError.path` | Source-breaking typed path. |
| Runtime binary compatibility | Not provided. |
| Kotlin/Native server code | New in 3.0. |

Run `./gradlew check` after the migration. Also run at least one test for each host-compatible platform family.
