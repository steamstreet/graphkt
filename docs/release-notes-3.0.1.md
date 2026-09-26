# GraphKt 3.0.1 release notes

Status: Unreleased

GraphKt 3.0 is a breaking release. It adds a common GraphQL server engine and generated Kotlin/Native server APIs.

3.0.1 is the first published 3.x release. The `v3.0.0` tag exists, but 3.0.0 was never published to Maven Central. 3.0.1 contains everything below, plus the changes in [Changes since the 3.0.0 tag](#changes-since-the-300-tag).

## Maven coordinates

GraphKt now publishes under the Maven group `com.steamstreet.graphkt`, and artifacts no longer carry a `graphkt-` prefix:

```kotlin
implementation("com.steamstreet.graphkt:client-ktor:3.0.1")
implementation("com.steamstreet.graphkt:server:3.0.1")
```

Through 2.x the artifacts published as `com.steamstreet:graphkt-<module>`. Those coordinates remain on Maven Central and do not receive 3.x releases. The plugin ID stays `com.steamstreet.graphkt`. The plugin implementation publishes as `com.steamstreet.graphkt:gradle-plugin`. The [migration guide](migration-2.x-to-3.0.md#2-update-runtime-dependencies) lists every coordinate.

## Highlights

- Generated client and server code now compiles for JVM, JavaScript, Apple, Linux, and MinGW targets.
- The common server parses, validates, coerces, and executes GraphQL operations without GraphQL Java.
- Each valid operation creates one request context and one lightweight resolver tree.
- Query-root fields run concurrently with a default limit of 16.
- Mutation-root fields run serially in document order.
- Request-scoped loaders batch concurrent keys and cache values for one operation.
- Generated subscriptions use `Flow` for source events and typed client responses.
- Ktor server routes now use the common `GraphQLServer` contract.
- The direct client now supports all core runtime targets and subscriptions.
- The Gradle plugin connects generated code to KMP `commonMain` and Kotlin/JVM `main`.

## Security changes

- Unexpected resolver failures return `Internal Server Error` by default.
- Public responses exclude exception messages and stack traces by default.
- Parsing and validation apply finite byte, token, depth, field, alias, and fragment limits.
- The server validates the complete operation before it constructs application resolvers.
- Cancellation remains cancellation and does not become a GraphQL error.
- Ktor and Lambda enforce UTF-8 JSON POST bodies and response media negotiation.

Applications must still implement authentication, authorization, and field-level access policy.

## Breaking changes

### Generated server construction

Generated applications now create a `GraphQLServer` with `graphKtServer` and `ResolverFactory`.

```kotlin
val server = graphKtServer(
    query = ResolverFactory { context -> QueryResolver(context) },
)
```

Generated resolver methods no longer receive request context. The factory stores context in request-scoped resolver objects.

### Request state

The common execution model does not use `ThreadLocal` or `gqlRequestContext()`. Use the context factory and resolver constructors.

### Optional client inputs

Nullable arguments and arguments with schema defaults can use `OptionalInput`. This type separates omission from an explicit `null` value.

### Error paths

`GraphQLError.path` now uses `GraphQLPathSegment`. Paths preserve field names and integer list indexes.

### Ktor routes

New routes accept `GraphQLServer` and a request-context factory:

```kotlin
route("/graphql") {
    graphQL(server) { call -> createRequestContext(call) }
}
```

The JVM callback route remains as a compatibility entry point. New 3.0 code must use the common server route.

### Lambda handlers

New handlers accept `GraphQLServer` and a request-context factory. The old callback DSL remains as a JVM compatibility entry point.

### Gradle configuration

Use `graphKt`, `schemaFiles`, `packageName`, and lazy feature properties. The old extension and eager properties remain deprecated aliases.

Remove manual generated-source directories and task dependencies. The plugin now supplies both.

## GraphQL behavior

- The runtime targets the [September 2025 GraphQL specification](https://spec.graphql.org/September2025/).
- Validation includes field merging, fragments, variables, directives, input objects, interfaces, unions, and OneOf inputs.
- Nullable boundaries and list indexes produce specification-shaped error paths.
- A failed non-null field nulls its nearest nullable ancestor, as the specification requires. 2.x omitted the failed field instead. When the null reaches the operation root, `data` is null and the recorded errors remain.
- HTTP execution results preserve an explicit `data: null` member.
- GET rejects mutation and subscription operations.
- Subscription operations require one root response key.

## Subscription scope

GraphKt provides common subscription execution and typed flows. It does not include a WebSocket or Server-Sent Events protocol.

Use `GraphQLDirectClient` for in-process subscriptions. A remote adapter can implement `GraphQLSubscriptionClient` for its selected protocol.

## Performance and hardening

The common deterministic fuzz suite runs 2,000 generated documents and 10 adversarial documents on supported runtime hosts.

The opt-in `:server:performanceBaseline` task measures parsing, validation, execution, batching, and response encoding. Results do not fail the build.

Read [the performance baseline](performance-baseline.md) before you compare runtime changes.

## Platform support

The common runtime, client, Ktor client, direct client, server, and Ktor server declare these target families:

- JVM
- JavaScript IR
- iOS
- macOS
- Linux
- Windows MinGW

The fetch client remains JavaScript-only. The Lambda adapter, generator, and Gradle plugin remain JVM-only.

## Migration

GraphKt 3.0 does not provide source or binary compatibility with 2.x generated server code.

Read the [2.x-to-3.0 migration guide](migration-2.x-to-3.0.md). Read the [Kotlin/Native guide](native.md) for target setup and sample commands.

## Changes since the 3.0.0 tag

- Artifacts publish under `com.steamstreet.graphkt`, as described in [Maven coordinates](#maven-coordinates).
- Generated resolver methods and input classes keep arguments and input fields in schema declaration order. The code at the 3.0.0 tag sorted them by name, which reordered the positional parameters of resolver overrides written for 2.x. An override still compiles after its parameters are reordered, and Kotlin only warns about the renamed parameters. Two parameters of the same type could therefore swap values silently.
- `GraphQLExecutionPolicy(fieldSelectionLookahead = true)` and `currentFieldSelection()` let a resolver look ahead at its selected subfields. They replace 2.x's `gqlContext`.
- JVM `ServerRequestSelection` gains an optional `GraphQLResolverErrorFactory`, and `ServerRequestSelection.forRootField` builds a selection from a separately supplied field, selection set, and arguments, as AWS AppSync resolvers deliver them. List, enum, and null argument literals, and omitted variables, no longer fail.
- A generated root `gqlSelect` driven through `ServerRequestSelection` returns `JsonNull` when a failed non-null root field makes the whole result null. It no longer throws an internal exception. The `Route.graphQL(block)` compatibility route then responds with `data: null` and the recorded errors instead of one generic error, and a new overload takes a `GraphQLResolverErrorFactory`.
- kotlinx.serialization moves from 1.10.0-RC to 1.10.0.

## Release checks

Before the final release:

1. Run `./gradlew check`.
2. Publish a local snapshot with `./gradlew snapshot`.
3. Run the host-compatible tests in `samples/native-client`.
4. Run the host-compatible tests in `samples/native-direct`.
5. Run two release candidates without a public API change.
6. Run `scripts/release.sh --dry-run`, which also checks that every published coordinate is under `com.steamstreet.graphkt`.
