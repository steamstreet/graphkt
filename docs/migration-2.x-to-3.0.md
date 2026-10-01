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

GraphKt 3.0 publishes under a new Maven group. Through 2.x the artifacts published as `com.steamstreet:graphkt-<module>`. From 3.0 on they publish as `com.steamstreet.graphkt:<module>`:

| 2.x coordinate | 3.0 coordinate |
|---|---|
| `com.steamstreet:graphkt-common-runtime` | `com.steamstreet.graphkt:common-runtime` |
| `com.steamstreet:graphkt-client` | `com.steamstreet.graphkt:client` |
| `com.steamstreet:graphkt-client-ktor` | `com.steamstreet.graphkt:client-ktor` |
| `com.steamstreet:graphkt-client-direct` | `com.steamstreet.graphkt:client-direct` |
| `com.steamstreet:graphkt-client-fetch` | `com.steamstreet.graphkt:client-fetch` |
| `com.steamstreet:graphkt-server` | `com.steamstreet.graphkt:server` |
| `com.steamstreet:graphkt-server-ktor` | `com.steamstreet.graphkt:server-ktor` |
| `com.steamstreet:graphkt-server-lambda` | `com.steamstreet.graphkt:server-lambda` |
| `com.steamstreet:graphkt-code-generator` | `com.steamstreet.graphkt:code-generator` |
| `com.steamstreet:graphkt-gradle-plugin` | `com.steamstreet.graphkt:gradle-plugin` |

Target-specific artifacts follow the same rule. For example, `graphkt-server-jvm` becomes `com.steamstreet.graphkt:server-jvm`, and `graphkt-client-ktor-iosarm64` becomes `com.steamstreet.graphkt:client-ktor-iosarm64`. Gradle selects these from the module metadata, so depend on the module coordinate and not on a target artifact.

The plugin ID stays `com.steamstreet.graphkt`, so `plugins { id("com.steamstreet.graphkt") version "3.0.1" }` needs only the version change. A build that puts the plugin on the classpath with `buildscript { dependencies { classpath(...) } }` must use `com.steamstreet.graphkt:gradle-plugin`.

Gradle does not treat the old and new coordinates as one module. Remove every 2.x coordinate, including any in version catalogs and dependency locks. A build that keeps one resolves both versions side by side, and the duplicate classes fail at compile time or at run time.

3.0.1 is the first published 3.x release. The `v3.0.0` tag exists, but 3.0.0 was never published to Maven Central.

If the application has Kotlin Multiplatform targets, use runtime artifacts in `commonMain`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.steamstreet.graphkt:client-ktor:3.0.1")
            implementation("com.steamstreet.graphkt:server:3.0.1")
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

Generated resolver methods and input classes list arguments and input fields in schema declaration order, as 2.x did. Keep override parameters in the same order as the schema.

### Replace `gqlContext` lookahead

GraphKt 2.x resolvers can read `gqlContext.get().children` to see which subfields a request selected. GraphKt 3.0 removes `gqlContext`. Call `currentFieldSelection()` from the resolver method, and enable lookahead in the execution policy:

```kotlin
val server = graphKtServer(
    query = ResolverFactory { context -> QueryResolver(context) },
    executionPolicy = GraphQLExecutionPolicy(fieldSelectionLookahead = true),
)

class QueryResolver(private val context: RequestContext) : Query {
    override suspend fun search(query: String): SearchResults {
        val requested = currentFieldSelection()?.children.orEmpty().map { it.name }.toSet()
        return SearchResults(
            events = if ("events" in requested) context.events.search(query) else emptyList(),
        )
    }
}
```

Lookahead is off by default because it adds a coroutine context switch to every field. With lookahead off, `currentFieldSelection()` returns null. The JVM compatibility selection, `ServerRequestSelection`, always exposes the selection.

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

A resolver that calls Kotlin's `TODO()` throws `NotImplementedError`, which is an `Error` rather than an `Exception`. GraphKt records it as a field error and applies null propagation, as 2.x did. Cancellation and other errors, such as `OutOfMemoryError` and `StackOverflowError`, are not recorded. They propagate out of the request.

JVM code that still calls generated `gqlSelect` functions through `ServerRequestSelection` records the same generic error. 2.x recorded the exception message and a `stacktrace` extension. To restore that behavior where errors never reach an untrusted client, pass an error factory:

```kotlin
val selection = ServerRequestSelection(
    parent = null,
    variables = variables,
    node = parseGraphQLOperation(query).selectionSet,
    errors = errors,
    errorFactory = GraphQLResolverErrorFactory.WithExceptionDetails,
)
```

`ServerRequestSelection.forRootField` builds a selection for one root field when the transport supplies the field's selection set and resolved arguments separately. AWS AppSync HTTP and Lambda resolvers deliver requests this way:

```kotlin
val root = ServerRequestSelection.forRootField(
    fieldName = info.fieldName,
    selectionSet = info.selectionSetGraphQL,
    arguments = arguments,
    variables = info.variables.orEmpty(),
    errors = errors,
)
// The root is JsonNull when a failed non-null root field made the whole result null.
val value = (QueryResolver(context).gqlSelect(root) as? JsonObject)?.get(info.fieldName) ?: JsonNull
```

Read the root result as described in [Handle null propagation](#handle-null-propagation). Do not call `jsonObject` on it without checking for `JsonNull`.

AppSync applies the aliases in the client's request itself. It reads each field of the returned value by its field name. A `forRootField` selection therefore keys every field in the result by its field name, as 2.x did, and ignores the aliases in `selectionSetGraphQL`. Error paths still use the aliases. Other `ServerRequestSelection` instances key fields by alias, as GraphQL execution requires. Pass `keyByFieldName = true` to the constructor to get the AppSync behavior for another selection.

One result cannot serve two aliases of the same field. For example, `first: search(limit: 1) { id }` and `all: search { id }` share the key `search`, and the last one selected overwrites the other. AppSync then returns that value for both aliases. When clients need such aliases, give the nested field its own AppSync resolver.

This replaces a hand-written `RequestSelection` that wraps a `ServerRequestSelection` for the selection set. Such a wrapper delegates `responseName`, `path`, and `forIndex` to a node that has no field name, and it fails on 3.0.

`ServerRequestSelection` requires GraphQL Java and is JVM-only. From 3.1.0, common code uses `RequestSelection.forRootField`, which takes the same parameters and has the same contract, on every target. A Kotlin/Native AppSync Lambda uses it:

```kotlin
val root = RequestSelection.forRootField(
    fieldName = info.fieldName,
    selectionSet = info.selectionSetGraphQL,
    arguments = arguments,
    variables = info.variables.orEmpty(),
    errors = errors,
    errorFactory = GraphQLResolverErrorFactory.WithExceptionDetails,
)
```

`GraphQLResolverErrorFactory` moves to common code in 3.1.0 and keeps its name and package. On Kotlin/Native, the `stacktrace` extension of `WithExceptionDetails` holds the native stack trace.

Neither version supports named fragment spreads. AppSync keeps a spread such as `...eventFields` in `selectionSetGraphQL`, but it omits the fragment's definition, so the fields that the spread selects are unknown. `RequestSelection.forRootField` throws an `IllegalArgumentException` that names the fragment before any resolver runs. The JVM version treats the spread as a field of that name, and the generated `gqlSelect` then throws an `IllegalArgumentException` for an unknown field, which fails the whole call. Clients of an AppSync API served this way must select those fields directly or in inline fragments. Neither version applies `@skip` or `@include`.

### Handle null propagation

GraphKt 2.x left a failed field out of its parent object and recorded an error. The field was omitted even when the schema declared it non-null, so a response could lack a field that the schema promised.

GraphKt 3.0 follows GraphQL null propagation. A failed field, or a non-null field that resolves to null, records an error at the field's path and becomes null. When the field is non-null, the null propagates to the nearest nullable ancestor field or list element, which becomes null in place of its object. The error path still names the field that failed. For example, with `event: Event` and `title: String!`, a failed `title` makes the response `{"data": {"event": null}, "errors": [{"path": ["event", "title"], ...}]}`.

When no nullable ancestor exists below the operation root, the whole `data` entry becomes null. `GraphQLServer`, the common Ktor route, and the Lambda handler then return `{"data": null, "errors": [...]}` with every recorded error.

JVM code that calls a generated root `gqlSelect` through `ServerRequestSelection` gets the same result. A generated `gqlSelect` returns a `JsonObject`, except that it returns `JsonNull` when a null propagates past a non-null root field. Pass the result and the collected errors to `buildResponse`, which produces `{"data": null, "errors": [...]}`. `gqlSelect` does not throw for a failed field. The `Route.graphQL(block)` compatibility route and the JVM `GraphQLLambda` callback DSL already respond this way.

Because 2.x never produced a null for a failed non-null field, review clients that read non-null fields. Make the schema field nullable where a partial response is still useful.

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
| Maven coordinates | Changed from `com.steamstreet:graphkt-<module>` to `com.steamstreet.graphkt:<module>`. |
| `gqlRequestContext()` | Removed from the 3.0 execution model. |
| `gqlContext` lookahead | Replaced by `currentFieldSelection()` with `fieldSelectionLookahead` enabled. |
| Ktor callback routes | JVM compatibility entry points remain. New code must use `GraphQLServer`. |
| Lambda callback DSL | JVM compatibility entry points remain. New code must use `GraphQLServer`. |
| `GraphQLError.path` | Source-breaking typed path. |
| Failed non-null fields | 2.x omitted them. 3.0 nulls the nearest nullable ancestor, or all of `data`. |
| `TODO()` in a resolver | Recorded as a field error, as in 2.x. Other `Error` types propagate. |
| Aliases through `forRootField` | Keyed by field name for AppSync, as in 2.x. |
| Runtime binary compatibility | Not provided. |
| Kotlin/Native server code | New in 3.0. |

Run `./gradlew check` after the migration. Also run at least one test for each host-compatible platform family.
