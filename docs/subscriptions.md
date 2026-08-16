# GraphKt 3.0 Subscriptions

GraphKt implements subscription execution in common Kotlin code. Generated clients and servers use `kotlinx.coroutines.flow.Flow`.

The [September 2025 GraphQL specification](https://spec.graphql.org/September2025/#sec-Subscription) defines stream behavior but does not define a network protocol.

GraphKt 3.0 does not include a WebSocket or Server-Sent Events protocol. Applications can connect the common flow to their selected protocol.

## Generated server API

Each field on the subscription root returns a source flow. The field remains a suspend function for consistency with other resolver fields.

```kotlin
class SubscriptionResolver(
    private val messages: MessageService,
) : Subscription {
    override suspend fun messageAdded(roomId: ID): Flow<Message> =
        messages.events(roomId).map(::MessageResolver)
}

val server = graphKtServer(
    query = ResolverFactory { context -> QueryResolver(context) },
    subscription = ResolverFactory { context -> SubscriptionResolver(context.messages) },
)
```

`GraphQLServer.subscribe` returns a cold flow of response envelopes.

```kotlin
server.subscribe(request) {
    createRequestContext()
}.collect { response ->
    transport.send(response)
}
```

Collection starts request parsing, validation, and variable coercion. An invalid request emits one error envelope and completes.

The server creates the request context and resolver tree once. Both objects remain active until the response flow completes or stops.

Each source event produces one `GraphQLResponseEnvelope`. Resolver errors affect only the current event.

If the collector cancels the response flow, GraphKt cancels the source flow. Then GraphKt releases all request-scope resources.

A source-flow failure stops the response flow with that failure. GraphKt does not expose the failure text in a GraphQL response.

## Generated client API

Generated subscription functions use `GraphQLSubscriptionClient`. Each response event becomes one generated response value.

```kotlin
val updates: Flow<Subscription> = client.subscription(name = "MessageUpdates") {
    messageAdded(roomId) {
        id
        text
    }
}
```

`GraphQLDirectClient` implements `GraphQLClient` and `GraphQLSubscriptionClient`. It connects a client to a `GraphQLServer` without a network.

HTTP clients implement `GraphQLClient` only. A streaming network adapter must implement `GraphQLSubscriptionClient`.

## Validation rules

A subscription operation must select exactly one root response key. Repeated selections with the same response key can merge.

The root field must not be an introspection field. Root selections must not use `@skip` or `@include`.

These rules also apply to root fields inside fragments.

## Target matrix

| Artifact | Targets | Subscription support |
|---|---|---|
| `common-runtime` | JVM, JavaScript, Apple, Linux, MinGW | Request and response models |
| `client` | JVM, JavaScript, Apple, Linux, MinGW | `GraphQLSubscriptionClient` contract |
| `server` | JVM, JavaScript, Apple, Linux, MinGW | Common subscription execution |
| `client-direct` | JVM, JavaScript, Apple, Linux, MinGW | Queries, mutations, subscriptions |
| `client-ktor` | JVM, JavaScript, Apple, Linux, MinGW | Queries and mutations |
| `server-ktor` | JVM, JavaScript, Apple, Linux, MinGW | Query and mutation HTTP routes |
| `client-fetch` | JavaScript | Queries and mutations |
| `server-lambda` | JVM | Queries and mutations |

The Apple group includes the configured iOS and macOS targets. The Linux group includes x64 and Arm64.

The Ktor server adapter exposes common route code. A Native application supplies the Ktor CIO server engine.
