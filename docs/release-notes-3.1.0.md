# GraphKt 3.1.0 release notes

Status: Unreleased

GraphKt 3.1.0 adds a common-code way to build AWS AppSync root-field selections, so an AppSync Lambda can run on Kotlin/Native. It is source and binary compatible with 3.0.1.

## Root-field selections in common code

`RequestSelection.forRootField` builds the selection for one root field from AppSync's `info.fieldName`, `info.selectionSetGraphQL`, `arguments`, and `info.variables`. It is the common equivalent of the JVM `ServerRequestSelection.forRootField`. It takes the same parameters and returns a selection for the generated root `gqlSelect`. It runs on every target that the `server` artifact supports, including `linuxArm64`.

```kotlin
val errors = mutableListOf<GraphQLError>()
val root = RequestSelection.forRootField(
    fieldName = info.fieldName,
    selectionSet = info.selectionSetGraphQL,
    arguments = arguments,
    variables = info.variables.orEmpty(),
    errors = errors,
    errorFactory = GraphQLResolverErrorFactory.WithExceptionDetails,
)
val value = (QueryResolver(context).gqlSelect(root) as? JsonObject)?.get(info.fieldName) ?: JsonNull
```

It follows the JVM version's contract:

- Each argument reaches the resolver unchanged, through a variable of its own, rather than as a GraphQL literal.
- Every field in the result is keyed by its field name, because AppSync applies aliases itself. Error paths use the aliases.
- An inline fragment's type condition becomes the `typeName` of the fields that it selects.
- A resolver that throws, including one that calls `TODO()`, records an error and fails only its own field. Null propagation then applies, and `gqlSelect` returns `JsonNull` when the null reaches the root.
- A null or blank selection set selects a field of a leaf type.

A JVM test drives generated `gqlSelect` code through both versions with identical inputs, and requires identical responses and errors. It covers aliases, nested arguments, inline fragments, variables, null propagation at the root, and throwing resolvers.

The JVM `ServerRequestSelection.forRootField` is unchanged.

## Error factory in common code

`GraphQLResolverErrorFactory` moves from the JVM source set to common code. Its name, package, and members are unchanged, so JVM callers need no change. `WithExceptionDetails` records the exception message, which an AppSync client sees, and a `stacktrace` extension. On Kotlin/Native the extension holds the native stack trace.

## Limitations

- Named fragment spreads are not supported. AppSync keeps a spread such as `...eventFields` in `selectionSetGraphQL`, but it omits the fragment's definition, so the fields that the spread selects are unknown. `RequestSelection.forRootField` throws an `IllegalArgumentException` that names the fragment before any resolver runs. The JVM version treats the spread as a field of that name, and the generated `gqlSelect` then throws for an unknown field. Clients must select those fields directly or in inline fragments.
- As in the JVM version, the selection is not validated against the schema, and `@skip` and `@include` are not applied.
- One result cannot serve two aliases of the same field with different arguments, as in 3.0.1. The [migration guide](migration-2.x-to-3.0.md#10-configure-public-resolver-errors) describes the limitation.
- A float literal in exponent form inside the selection set, such as `1e3`, keeps its literal text. The JVM version rewrites it as `1E+3`. Both decode to the same number.

## Platform artifacts

The `server` artifact already publishes a `server-linuxarm64` variant, and 3.0.1 includes it on Maven Central. A consumer's Gradle cache holds a target artifact only after a build has resolved it for that target, so a cache with `server-linuxx64` and `server-macosarm64` but no `server-linuxarm64` does not mean that the artifact is missing.

## Release checks

Before the final release:

1. Run `./gradlew check`.
2. Run `./gradlew :server:compileKotlinLinuxArm64`, which cross-compiles on a macOS host.
3. Run `scripts/release.sh --scope minor --dry-run`, which also checks that every published coordinate is under `com.steamstreet.graphkt`, and confirm that the scratch repository holds `server-linuxarm64`.
4. Release with `scripts/release.sh --scope minor`.
