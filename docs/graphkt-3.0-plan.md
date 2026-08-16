# GraphKt 3.0.x Plan

Status: Active
Target: GraphKt 3.0.0
Scope: Code generator, common runtime, client runtime, server runtime, transports, and Gradle plugin

## Implementation progress

The `3.0.x` branch now contains the first generator and platform changes.

Completed work:

- One generation service now serves the command-line entry point and the Gradle task.
- All code emitters now use the normalized schema model.
- Client generation supports nested lists, custom operation roots, interfaces, and unions.
- Server generation produces clean resolver interfaces without a request-context parameter.
- Generated server mapping code no longer uses thread-local request state.
- The server module now declares the same Kotlin/Native targets as the client and common runtime.
- Resolver errors no longer return exception messages or stack traces by default.
- The common runtime defines request envelopes, response envelopes, typed error paths, and omitted inputs.
- Generated client methods preserve omitted arguments, explicit `null` values, and schema defaults.
- A common parser accepts executable documents and selects named operations.
- The parser limits document bytes, token count, and syntax nesting.
- Generated server code includes platform-neutral schema metadata.
- The common validator checks fields, arguments, fragments, variables, input values, and OneOf inputs.
- Validation limits cover selection depth, field count, aliases, fragment expansion, and fragment nesting.
- The common coercer preserves omitted variables, explicit `null` values, and schema defaults.
- The request-preparation path completes parsing, validation, and coercion before resolver construction.
- The common server collects selected fields after it evaluates aliases, fragments, `@skip`, and `@include`.
- Field collection merges repeated selections for each possible concrete object type.
- The common server executes generated query and mutation resolvers with coerced arguments.
- The common server applies nullable boundaries and includes field names and list indexes in error paths.
- Cancellation passes through the execution engine without conversion to a GraphQL error.
- Generated `graphKtServer` functions create one root resolver tree for each valid request.
- The Ktor server transport accepts the common `GraphQLServer` and returns common response envelopes.
- Ktor transport tests cover malformed envelopes and safe resolver errors.
- The Lambda transport accepts the common `GraphQLServer` for API Gateway v2 HTTP events.
- The Lambda transport supports JSON bodies, base64 bodies, GET parameters, and operation names.
- HTTP GET requests reject mutation operations before context construction.
- Ktor and Lambda create application context only after request preparation succeeds.
- Ktor and Lambda negotiate `application/graphql-response+json` and `application/json` responses.
- Ktor and Lambda require UTF-8 `application/json` for POST request bodies.
- HTTP adapters distinguish transport, document, request, and execution status codes.
- Executed responses preserve an explicit `data: null` member.
- The Ktor client sends JSON envelopes and prefers the GraphQL response media type.
- Field-merging validation expands fragments and compares nested response shapes.
- Field-merging validation permits different fields on mutually exclusive object types.
- Generated schema metadata includes custom directive definitions.
- The common validator validates custom directive locations, repeatability, arguments, defaults, and variables.
- `GraphQLDirectiveHandler` adds field behavior without changing generated resolver methods.
- The common server runs independent query-root fields concurrently.
- `GraphQLExecutionPolicy` sets a finite query parallelism limit of 16 by default.
- The common server runs mutation-root fields serially in document order.
- The common server returns a cold response `Flow` for each subscription operation.
- One request scope, context, and resolver tree serve the full subscription flow.
- Generated subscription resolver fields return typed source flows.
- Generated subscription clients parse one typed result for each response event.
- Subscription validation requires one root response key and rejects conditional root selections.
- Subscription cancellation stops the source flow and releases request resources.
- The direct client supports queries, mutations, and subscriptions on all core runtime targets.
- The Ktor server route extension compiles for JVM, JavaScript, and the supported Native targets.
- One request-scoped root resolver serves all fields in an operation.
- Concurrent resolver errors retain GraphQL response order.
- Each valid operation owns a `GraphQLRequestScope`.
- The request scope provides cached loaders that combine keys from concurrent fields.
- Request loader caches do not persist across operations.
- The request scope releases resources in reverse registration order.
- Resource cleanup runs after successful execution, execution failure, context failure, or cancellation.
- Ktor and Lambda context factories receive the common request scope.
- Deterministic common fuzz tests exercise 2,000 generated documents and 10 adversarial documents.
- The fuzz suite uses the same pseudo-random sequence on JVM, JavaScript, and Native targets.
- The `performanceBaseline` task records repeatable JVM parsing, validation, execution, batching, and response-encoding measurements.
- The initial runtime baseline uses macOS AArch64 and JDK 17.0.10.
- JavaScript, macOS, and iOS Simulator run the common conformance tests.
- Linux Arm64 and Windows MinGW compile the common validation and coercion code.
- Generator failures provide structured diagnostics with source locations.
- The generator stages output and replaces old output only after successful generation.
- The Gradle generation task supports the build cache and tracks all schema files.
- Gradle TestKit covers up-to-date runs, schema changes, and cache restoration.
- The Gradle extension uses lazy properties for schema inputs, package names, and feature flags.
- The Gradle plugin adds generated sources to KMP `commonMain` and Kotlin/JVM `main`.
- Kotlin compilations depend on generation through source-directory providers.
- The Gradle plugin does not require one Kotlin Gradle plugin version at runtime.
- Gradle TestKit covers KMP source-set wiring and removal of disabled server output.
- Generated-source fixtures compile client and server output together.
- The repository-wide Gradle check passes for JVM, JavaScript, and the available Native targets.

Remaining work:

- Write the migration guide, Native samples, API reference, and release notes.

## 1. Purpose

GraphKt 3.0 will provide type-safe GraphQL clients and servers for Kotlin Multiplatform.

All platform-neutral runtime libraries and generated runtime code will support Kotlin/Native. The build-time generator can remain a JVM tool.

Version 3.0.0 can change public APIs. The 3.0.x patch releases will keep the final 3.0 API compatible.

## 2. Accepted decisions

The following decisions define the 3.0 architecture:

- The generator runs at build time on the JVM.
- Generated client and server code runs on supported Kotlin Multiplatform targets.
- The server execution core resides in `commonMain`.
- The runtime has no dependency on GraphQL Java, Java reflection, or JVM thread state.
- Each request creates a request-scoped resolver graph.
- The server creates nested resolvers only for selected fields.
- Resolver instances are small objects that contain shared service references and request data.
- Applications define their request-context type.
- Each valid operation owns one common request scope.
- The request scope owns loader caches and registered cleanup actions.
- The server passes the request context to a root-resolver factory once per request.
- Generated resolver methods do not contain a context parameter.
- The runtime does not use `ThreadLocal` for request state.
- GraphKt returns safe errors by default and keeps internal error details on the server.
- The generated API will support all GraphQL type wrappers without string-based type decisions.
- GraphKt targets the September 2025 GraphQL specification.
- GraphKt uses one focused recursive-descent parser in `commonMain`.
- The parser applies finite document, token, and syntax-nesting limits.
- Nullable client arguments use `OptionalInput` when omission changes behavior.
- Server fields use suspend functions for one consistent resolver contract.
- Subscription root fields return `Flow<T>` from suspend resolver functions.
- `GraphQLServer.subscribe` returns a cold `Flow<GraphQLResponseEnvelope>`.
- GraphKt 3.0 does not select a WebSocket or SSE subscription protocol.

## 3. Goals

GraphKt 3.0 has the following goals:

1. Generate Kotlin code that compiles for JVM, JS, and supported Native targets.
2. Provide a common GraphQL execution core for client and server use.
3. Preserve GraphQL nullability, list structure, defaults, unions, interfaces, and input coercion.
4. Validate each operation before resolver execution.
5. Apply finite limits to untrusted GraphQL documents.
6. Isolate request state across coroutines, threads, and Native workers.
7. Produce deterministic source files and GraphQL documents.
8. Support concurrent query-field execution and serial mutation-root execution.
9. Support request-scoped batching without a JVM dependency.
10. Give Gradle complete task inputs, outputs, and source-set wiring.
11. Compile generated code during automated tests on each supported platform family.
12. Provide a direct migration guide from GraphKt 2.x.

## 4. Non-goals

The following work is outside the initial 3.0.0 scope:

- The code generator does not run inside a Kotlin/Native process.
- GraphKt does not provide authentication or authorization policy.
- GraphKt does not own database, HTTP-client, or dependency-injection lifecycles.
- GraphKt does not hide transport-specific request objects inside the common execution core.
- GraphKt does not preserve binary compatibility with GraphKt 2.x.
- GraphKt does not expose internal schema or execution types without a documented extension requirement.

Remote subscription protocols remain outside the initial 3.0.0 scope. Section 15 records this decision.

## 5. Proposed module structure

```text
graphkt-generator         JVM build-time schema compiler and Kotlin emitter
graphkt-gradle-plugin     JVM Gradle integration
graphkt-runtime           common AST, values, errors, validation, and execution
graphkt-client            common query API and response handling
graphkt-server            common resolver contracts and server execution
graphkt-client-ktor       Ktor client transport for supported targets
graphkt-server-ktor       Ktor server transport for supported targets
graphkt-client-fetch      Optional browser transport
graphkt-client-direct     Common in-process transport
graphkt-server-lambda     Optional platform adapter
graphkt-testing           Common test utilities for generated schemas and resolvers
```

Platform adapters can add source sets when a platform needs a specific implementation. The common runtime remains independent of these adapters.

## 6. Generation architecture

### 6.1 One generation pipeline

The generator will have one public generation service. The Gradle task and test utilities will call this service.

```kotlin
data class GenerationRequest(
    val schemaFiles: List<Path>,
    val packageName: String,
    val scalarMappings: Map<String, ScalarMapping>,
    val features: GenerationFeatures,
    val outputs: GenerationOutputs,
)

interface GraphKtGenerator {
    fun generate(request: GenerationRequest): GenerationResult
}
```

`GenerationResult` will contain diagnostics, generated files, and schema metadata. It will not mutate Gradle project state.

### 6.2 Normalized schema model

The generator will convert the parsed schema into one normalized intermediate representation.

```kotlin
sealed interface TypeRef {
    data class Named(val name: String) : TypeRef
    data class ListType(val element: TypeRef) : TypeRef
    data class NonNull(val value: TypeRef) : TypeRef
}
```

The model will include these definitions:

- Objects
- Interfaces
- Unions
- Enums
- Input objects
- Scalars
- Directives
- Schema roots
- Field arguments
- Default values
- Documentation and deprecation data

All emitters will use the normalized model. Emitters will not search raw schema collections or manually unwrap type nodes.

### 6.3 Deterministic output

The generator will sort files, imports, definitions, and variables with documented rules. Identical inputs will produce byte-identical outputs.

The generator will write files to a staging directory. It will replace the target directory after successful generation.

This process prevents partial output and stale files. It also gives Gradle stable build-cache entries.

## 7. Generated server API

### 7.1 Resolver interfaces

Generated interfaces will contain only schema-defined fields:

```kotlin
interface Query {
    suspend fun user(id: ID): User?
    suspend fun search(term: String?): List<SearchResult>
}

interface User {
    suspend fun id(): ID
    suspend fun name(): String
    suspend fun email(): String?
}

interface SearchResult {
    suspend fun score(): Float
}
```

The generator can use properties for fields that cannot suspend and have no arguments. One consistent rule must apply to all generated schemas.

### 7.2 Request-scoped resolver graph

The application creates the request context and root resolver once per operation:

```kotlin
route.graphQL(server) { call ->
    val transaction = database.openTransaction()
    onClose { transaction.close() }

    AppRequestContext(
        principal = authenticate(call),
        usersById = batchLoader(
            BatchLoader { ids -> users.find(ids).associateBy(UserRecord::id) },
        ),
        transaction = transaction,
    )
}
```

The root resolver will create nested resolvers only when selected fields need them:

```kotlin
class QueryResolver(
    private val context: AppRequestContext,
) : Query {
    override suspend fun user(id: ID): User? {
        return context.usersById.load(id)?.let { UserResolver(context, it) }
    }
}
```

The resolver graph contains lightweight wrappers. Repositories, connection pools, and HTTP clients remain shared application services.

### 7.3 Resolver lifecycle

The server owns one request scope for each valid operation. The scope ends after execution or cancellation.

The request scope owns these items:

- The application context
- Batching caches
- Operation variables
- Error collection
- Cancellation state
- Execution metrics

The server releases registered resources in reverse registration order. Cleanup runs in a non-cancellable context.

The server runs all cleanup actions when one action fails. A normal request reports these failures through `GraphQLRequestCleanupException`.

Cleanup does not replace an active context failure or cancellation error. The original error remains the request result.

Cleanup failures attach to the original error as suppressed errors.

## 8. Input and output semantics

### 8.1 Omitted input and explicit null

GraphQL distinguishes an omitted value from an explicit `null` value. The client API must preserve this distinction.

```kotlin
sealed interface OptionalInput<out T> {
    data object Absent : OptionalInput<Nothing>
    data class Present<T>(val value: T) : OptionalInput<T>
}
```

The generator will use `OptionalInput` only where omission changes GraphQL behavior. The executor will apply schema defaults before resolver execution.

Server resolver methods will receive coerced values. Most server APIs will not expose `OptionalInput`.

### 8.2 Complete type support

The generated client and server APIs will support these shapes:

- Nullable and non-null named types
- Nullable and non-null lists
- Nullable and non-null list elements
- Nested lists
- Lists of scalars, enums, and input objects
- Interfaces and unions
- Custom scalars
- Schema default values

The compiler will report an error for each unsupported schema feature. It will not emit incomplete Kotlin code.

### 8.3 Polymorphic values

Generated unions and interfaces will use sealed Kotlin APIs where the target permits them.

Unknown server types will produce an execution error. Unknown client types will use a documented forward-compatible representation.

## 9. Common execution core

### 9.1 Request flow

Each server request will use this flow:

1. Decode the transport envelope.
2. Parse the GraphQL document.
3. Select the requested operation.
4. Validate the operation against generated schema metadata.
5. Coerce variables and apply default values.
6. Apply document and complexity limits.
7. Create the application context and root resolver.
8. Execute the selected fields.
9. Apply GraphQL null propagation and error paths.
10. Create the response envelope.
11. Release request-scoped resources.
12. Encode the transport response.

The common core will not depend on Ktor, AWS Lambda, servlet APIs, or browser APIs.

### 9.2 GraphQL behavior

The 3.0 executor will support these behaviors before the final release:

- Operation names
- Field aliases
- Named fragments
- Inline fragments
- Variables
- Input literals
- Directives required by the selected compatibility target
- Field merging
- Interface and union selections
- GraphQL error paths, including list indexes
- Non-null error propagation
- Query and mutation operation rules

The project will publish its compatibility target and known differences.

### 9.3 Parser decision

The runtime uses a focused recursive-descent parser in `commonMain`.

Apollo AST 5.0.1 supports most required targets, but it does not publish a `mingwX64` variant. GraphKt keeps Windows Native support.

The first parser version accepts executable documents. The build-time generator continues to parse schemas and produces schema metadata for runtime validation.

The parser has finite document, token, and syntax-nesting limits. Fuzz tests cover malformed and adversarial documents.

## 10. Security model

### 10.1 Public errors

GraphKt will not return stack traces or raw internal exceptions by default.

```kotlin
fun interface ErrorMapper<C> {
    fun map(context: C, error: ResolverFailure): GraphQLError
}
```

The default mapper will return a stable public message and correlation identifier. The application logger will receive the internal exception.

### 10.2 Execution limits

The server configuration will contain finite defaults for these limits:

- Document bytes
- Token count
- Syntax nesting
- Selection depth
- Selected field count
- Alias count
- Fragment expansion
- Operation complexity
- Variable bytes
- Response bytes, when the transport supports this limit

The project will select default values from benchmark and security-test results.

### 10.3 Cancellation

The executor will propagate coroutine cancellation. It will not convert cancellation or fatal platform errors into GraphQL field errors.

### 10.4 Application policy

Authentication and authorization remain application responsibilities. The request context provides the data that resolvers need for these policies.

## 11. Performance model

### 11.1 Field execution

The executor runs independent query-root fields concurrently. It limits the number of active query fields to 16 by default.

Applications can set `GraphQLExecutionPolicy.maximumQueryParallelism`. A value of one keeps query execution serial.

The executor runs top-level mutation fields serially in document order. The executor does not start the next mutation field before the current field completes.

Concurrent fields share one request-scoped root resolver. The executor keeps response fields and resolver errors in GraphQL response order.

Nested fields can run concurrently after their parent value is available. A configurable execution policy will control concurrency limits.

### 11.2 Custom directives

The normalized schema includes custom directive definitions, arguments, locations, and repeatability. The common validator applies this metadata to executable directive uses.

`GraphQLDirectiveHandler` supplies runtime behavior for `FIELD` directives. The handler receives the request context, directive arguments, field selection, and continuation.

Handlers form a field-local chain in document order. Each handler can continue once, or it can return a replacement field value.

GraphKt gives no runtime behavior to custom directives that do not have a handler. Custom directives at other executable locations remain validation-only.

### 11.3 Batching

GraphKt provides a common request-scoped batching contract:

```kotlin
interface BatchLoader<K, V> {
    suspend fun load(keys: List<K>): Map<K, V>
}
```

`GraphQLRequestScope.batchLoader` creates a `RequestBatchLoader`. The loader combines unique keys from concurrent fields into one batch.

The loader caches values, missing values, and loader failures for one operation. The `clear`, `clearAll`, and `prime` functions control this cache.

`loadMany` loads keys concurrently and keeps input order in its result. Request cleanup clears the cache and rejects new loader work.

An application can put a shared cache inside its `BatchLoader`. GraphKt does not share its request cache between operations.

### 11.4 Allocation policy

Resolver wrappers can allocate per selected object. These wrappers must contain only request data, parent data, and shared service references.

Benchmarks will measure parser cost, validation cost, resolver allocation, batching, and response encoding. Performance gates will use recorded 2.x and 3.0 baselines.

### 11.5 Subscriptions

The common server uses `Flow` for subscription source streams and response streams. Collection starts request preparation for the flow.

The server creates one request scope, context, and resolver tree for the full flow. Each source event produces one independent response envelope.

Resolver errors become safe GraphQL errors for that event. A source-flow failure stops the response flow with the same internal failure.

Collector cancellation stops the source flow. Then the request scope releases its loaders and registered resources.

The validator requires exactly one subscription root response key. It rejects root introspection and root `@skip` or `@include` directives.

## 12. Client architecture

The client runtime will remain in `commonMain`. Transport adapters will implement one common request contract.

```kotlin
interface GraphQLTransport {
    suspend fun execute(request: GraphQLRequest): GraphQLResponseEnvelope
}
```

`GraphQLSubscriptionClient` defines a separate streaming contract. Generated subscription functions return a typed `Flow` through this contract.

The direct client implements both contracts on every core runtime target. The Ktor and fetch clients support one-shot HTTP operations only.

The request will contain `query`, `operationName`, `variables`, and optional extensions. JSON envelopes will use `application/json` by default.

The HTTP adapters accept `application/graphql-response+json` and `application/json` response types. They prefer `application/graphql-response+json` when the client accepts both types or omits the `Accept` header. POST bodies must use UTF-8 `application/json`.

Malformed JSON and GraphQL document syntax return status 400. Invalid request envelopes, validation failures, operation-selection failures, and variable-coercion failures return status 422. Executed operations return status 200, including responses that contain field errors. Unsupported media preferences return status 406. Unsupported POST content types return status 415. Unsupported HTTP methods and mutations sent with GET return status 405.

Transport failures that prevent a GraphQL response use `application/json`. Valid GraphQL responses use the negotiated response type.

Generated operations will have stable names and deterministic variable order. This behavior supports logs, caches, and persisted queries.

Response parsing will preserve aliases, error paths, polymorphic types, and absent fields. Generated parser state will use reserved names without schema collisions.

## 13. Gradle plugin

The Gradle plugin will use lazy Gradle properties:

```kotlin
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

The generation task will declare all schema files, scalar mappings, feature flags, package names, versions, and output directories.

The task will support the Gradle build cache. The plugin will connect generated directories to the selected Kotlin source sets.

For Kotlin Multiplatform projects, the plugin connects generated code to `commonMain`. For Kotlin/JVM projects, it connects generated code to `main`. A project does not need a manual source directory or task dependency.

The 2.x `GraphQL` extension and its eager properties remain as deprecated migration aliases. New 3.0 code must use `graphKt` and lazy properties.

The plugin will not find compilation tasks by task-name patterns. It will use Kotlin Gradle plugin APIs and task providers.

Client-only projects will not compile server output. Server-only projects will not compile client output.

## 14. Test strategy

### 14.1 Generator tests

Each schema fixture will produce deterministic golden files. Automated tests will compile generated sources instead of printing them.

The fixture matrix will include these cases:

- All nullability and list combinations
- Nested lists
- Scalars with and without custom serializers
- Enum lists
- Input-object lists
- Schema defaults
- Interfaces
- Unions
- Directives
- Kotlin keywords and internal-name collisions
- Multiple schema files
- Removed types and changed feature flags

### 14.2 Runtime conformance tests

The same request fixtures will execute on JVM, JS, and available Native hosts. Tests will compare data, errors, and paths.

The suite will cover aliases, fragments, variables, defaults, coercion, null propagation, cancellation, and operation selection.

### 14.3 Security tests

The suite will include malformed documents, deep documents, fragment cycles, alias expansion, oversized variables, and resolver exceptions.

Public responses must not contain stack traces, local paths, class names, or internal exception text.

The deterministic fuzz suite generates arbitrary documents and mutations of valid documents. Fixed seeds produce the same cases on each runtime.

Adversarial fixtures exercise byte, token, syntax-nesting, selection, alias, and fragment limits. Unexpected runtime errors fail the suite.

### 14.4 Platform matrix

The release pipeline will compile these target families:

- JVM
- JavaScript IR
- Linux X64 and Arm64
- macOS X64 and Arm64
- iOS Arm64, X64, and Simulator Arm64
- Windows MinGW X64

Host-compatible targets will execute runtime tests. Cross-compiled targets will compile test binaries when execution is unavailable.

### 14.5 Gradle plugin tests

Gradle TestKit will cover source-set wiring, task caching, changed configuration, changed schemas, removed schemas, and client-only generation.

The root build will run all applicable unit and integration suites. A successful root build must not hide KMP test tasks.

### 14.6 Performance baselines

The opt-in `:server:performanceBaseline` task measures parsing, validation, execution, request batching, and response encoding on the JVM. The normal `check` task does not run timing measurements.

The baseline document records the host, JDK, workloads, median time, p95 time, and throughput. Initial results are in `docs/performance-baseline.md`.

Timing results do not fail the build. A release comparison requires repeated measurements on the same host and JDK.

## 15. Open decisions

GraphKt 3.0 includes common subscription execution and typed generated flows. It does not include a bundled WebSocket or SSE protocol.

The core, Ktor client, Ktor server, and direct client use the configured JVM, JavaScript, Apple, Linux, and MinGW targets.

The browser fetch adapter remains JavaScript-only. The Lambda adapter remains JVM-only.

The project must resolve this decision before the specified milestone. `GraphQLDirectiveHandler` is the public extension API for field directives.

| Decision | Deadline |
|---|---|
| Select initial execution-limit defaults | Milestone 5 |

## 16. Delivery milestones

### Milestone 1: Generator foundation

Deliverables:

- One generation service
- Normalized schema model
- Deterministic output
- Complete Gradle task inputs
- Compiled fixture tests for existing supported features

Exit gate: Existing client schemas compile through the new pipeline without the new runtime.

### Milestone 2: Common document core

Deliverables:

- Common AST and value model
- Common parser
- Operation selection
- Schema validation
- Variable coercion
- Initial document limits

Exit gate: JVM, JS, and Native tests produce equal validation results for the same fixtures.

### Milestone 3: Generated 3.0 APIs

Deliverables:

- Request-scoped resolver factories
- Generated server interfaces
- Generated client operations
- Complete type-wrapper support
- Interfaces and unions
- Omitted-input support
- Migration examples

Exit gate: Representative applications compile for JVM, JS, Linux, macOS, and iOS.

### Milestone 4: Common execution engine

Deliverables:

- Field execution
- Null propagation
- Error paths
- Safe error mapping
- Cancellation propagation
- Concurrent query execution
- Serial mutation-root execution
- Request-scoped batching
- Subscription source and response flows
- Subscription cancellation and cleanup

Exit gate: End-to-end fixtures produce equal response envelopes on supported runtime hosts.

### Milestone 5: Transports and hardening

Deliverables:

- Ktor client transport
- Ktor server transport for supported targets
- Direct in-process transport
- Common Ktor server route API
- Security limits
- Fuzz tests
- Performance benchmarks
- Gradle TestKit suite

Exit gate: Security tests pass, and benchmark results have documented baselines.

### Milestone 6: Migration and release candidates

Deliverables:

- 2.x-to-3.0 migration guide
- API reference
- Native sample applications
- Release notes
- Compatibility statement
- Deprecation status for replaced 2.x modules

Exit gate: Two release candidates pass the complete platform matrix without a public API change.

## 17. Release sequence

The proposed release sequence is:

1. `3.0.0-alpha01`: Normalized generator and early common document core.
2. `3.0.0-alpha02`: Generated resolver APIs and common execution preview.
3. `3.0.0-beta01`: Complete feature scope and initial transports.
4. `3.0.0-beta02`: Security limits, batching, and migration guide.
5. `3.0.0-rc01`: Final API and complete platform matrix.
6. `3.0.0-rc02`: Release corrections only.
7. `3.0.0`: Stable 3.0 API.

After `3.0.0`, patch releases will contain compatible corrections and platform maintenance. New public features will use a later minor release.

## 18. Migration principles

The migration guide will use direct before-and-after examples. It will cover these changes:

- Global request context to request-scoped resolver construction
- Existing server interfaces to 3.0 resolver interfaces
- Existing client calls to named generated operations
- Nullable client arguments to omitted-input values
- Scalar mapping configuration
- Gradle extension configuration
- Output directory changes
- Safe error mapping
- Ktor and Lambda transport changes

GraphKt 2.x and 3.0 can use different generated package names during migration. This option permits staged application changes.

## 19. Definition of done for 3.0.0

GraphKt 3.0.0 is complete when all these conditions are true:

- Runtime artifacts contain no accidental JVM dependencies.
- Generated client and server code compiles for every declared target.
- The server uses no thread-local request state.
- Public errors contain no internal stack traces by default.
- The executor applies finite document and complexity limits.
- Union, interface, list, default, alias, and fragment fixtures pass.
- Cancellation tests pass on each executable platform family.
- Query concurrency and mutation ordering tests pass.
- Gradle caching and source-set tests pass.
- The root build runs the complete applicable test suite.
- The migration guide contains a working 2.x-to-3.0 application example.
- Two release candidates pass without a public API change.

## 20. First implementation work packages

Implementation will begin in this order after plan approval:

1. Add conformance fixtures that expose current generator and runtime gaps.
2. Define the normalized schema model and diagnostic model.
3. Move all generator entry points behind one generation service.
4. Correct Gradle task inputs, outputs, and source-set wiring.
5. Select the common parser and compatibility target.
6. Define the request envelope, response envelope, error model, and optional-input model.
7. Generate the request-scoped resolver API for one vertical schema fixture.
8. Implement one end-to-end query path in `commonMain`.
9. Add Native compilation and runtime gates before broader feature work.

Each work package must keep the repository buildable. Each API work package requires review before implementation.
