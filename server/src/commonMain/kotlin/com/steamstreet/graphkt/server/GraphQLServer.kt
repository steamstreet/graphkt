package com.steamstreet.graphkt.server

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.GraphQLPathSegment
import com.steamstreet.graphkt.GraphQLRequest
import com.steamstreet.graphkt.GraphQLResponseEnvelope
import com.steamstreet.graphkt.GraphQLResponseKind
import com.steamstreet.graphkt.server.execution.GraphQLDocumentLimits
import com.steamstreet.graphkt.server.execution.GraphQLDirectiveLocation
import com.steamstreet.graphkt.server.execution.GraphQLRequestPreparer
import com.steamstreet.graphkt.server.execution.GraphQLSchemaDefinition
import com.steamstreet.graphkt.server.execution.RequestPreparationFailureKind
import com.steamstreet.graphkt.server.execution.buildRequestSelection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException

/** Resolves one selected field on a request-scoped root resolver. */
public fun interface GraphQLRootFieldResolver {
    public suspend fun resolve(selection: RequestSelection): JsonElement?
}

/** Creates one root field resolver after request preparation succeeds. */
public fun interface GraphQLRootResolverFactory<in Context> {
    public fun create(context: Context): GraphQLRootFieldResolver
}

/** Converts an internal resolver failure into a public GraphQL error. */
public fun interface GraphQLExecutionErrorMapper<in Context> {
    public fun map(
        context: Context,
        failure: Throwable,
        path: List<GraphQLPathSegment>,
    ): GraphQLError
}

/** The selected operation type exposed to transport policy. */
public enum class GraphQLOperationType {
    QUERY,
    MUTATION,
    SUBSCRIPTION,
}

/** Indicates that transport policy does not allow the selected operation type. */
public class GraphQLOperationNotAllowedException(
    public val operationType: GraphQLOperationType,
) : IllegalArgumentException("Operation type '$operationType' is not allowed by this transport")

/** Controls how the common executor schedules fields. */
public data class GraphQLExecutionPolicy(
    val maximumQueryParallelism: Int = 16,
) {
    init {
        require(maximumQueryParallelism > 0) { "maximumQueryParallelism must be greater than zero" }
    }
}

/** Platform-neutral GraphQL request executor. */
public class GraphQLServer<Context>(
    private val schema: GraphQLSchemaDefinition,
    private val query: GraphQLRootResolverFactory<Context>,
    private val mutation: GraphQLRootResolverFactory<Context>? = null,
    private val subscription: GraphQLSubscriptionRootResolverFactory<Context>? = null,
    private val limits: GraphQLDocumentLimits = GraphQLDocumentLimits(),
    private val executionPolicy: GraphQLExecutionPolicy = GraphQLExecutionPolicy(),
    private val directiveHandlers: Map<String, GraphQLDirectiveHandler<Context>> = emptyMap(),
    private val errorMapper: GraphQLExecutionErrorMapper<Context> = GraphQLExecutionErrorMapper { _, _, path ->
        GraphQLError(message = "Internal Server Error", path = path)
    },
) {
    init {
        val invalidHandler = directiveHandlers.keys.firstOrNull { name ->
            GraphQLDirectiveLocation.FIELD !in schema.directive(name)?.locations.orEmpty()
        }
        require(invalidHandler == null) {
            "Directive handler '@$invalidHandler' does not match a FIELD directive in the schema"
        }
    }

    public suspend fun execute(request: GraphQLRequest, context: Context): GraphQLResponseEnvelope {
        return executeRequest(request, contextFactory = { context })
    }

    /** Executes a request and creates request-scoped context only after preparation succeeds. */
    public suspend fun execute(
        request: GraphQLRequest,
        contextFactory: suspend GraphQLRequestScope.() -> Context,
    ): GraphQLResponseEnvelope = executeRequest(request, contextFactory)

    /** Returns a cold response stream for one subscription operation. */
    public fun subscribe(request: GraphQLRequest, context: Context): Flow<GraphQLResponseEnvelope> {
        return subscribeRequest(request, contextFactory = { context })
    }

    /** Creates request-scoped context when the returned subscription stream starts collection. */
    public fun subscribe(
        request: GraphQLRequest,
        contextFactory: suspend GraphQLRequestScope.() -> Context,
    ): Flow<GraphQLResponseEnvelope> = subscribeRequest(request, contextFactory)

    /** Executes only query operations and rejects mutations and subscriptions. */
    public suspend fun executeQuery(request: GraphQLRequest, context: Context): GraphQLResponseEnvelope {
        return executeRequest(request, contextFactory = { context }, requiredOperationType = GraphQLOperationType.QUERY)
    }

    /** Executes only queries and creates request-scoped context after operation checks succeed. */
    public suspend fun executeQuery(
        request: GraphQLRequest,
        contextFactory: suspend GraphQLRequestScope.() -> Context,
    ): GraphQLResponseEnvelope = executeRequest(
        request = request,
        contextFactory = contextFactory,
        requiredOperationType = GraphQLOperationType.QUERY,
    )

    private suspend fun executeRequest(
        request: GraphQLRequest,
        contextFactory: suspend GraphQLRequestScope.() -> Context,
        requiredOperationType: GraphQLOperationType? = null,
    ): GraphQLResponseEnvelope {
        val preparation = GraphQLRequestPreparer(schema, limits).prepare(request)
        val prepared = preparation.operation
            ?: return GraphQLResponseEnvelope(
                errors = preparation.errors,
                kind = when (preparation.failureKind) {
                    RequestPreparationFailureKind.DOCUMENT -> GraphQLResponseKind.DOCUMENT_ERROR
                    RequestPreparationFailureKind.REQUEST, null -> GraphQLResponseKind.REQUEST_ERROR
                },
            )
        val operationType = prepared.operation.type.toPublicType()
        if (requiredOperationType != null && operationType != requiredOperationType) {
            throw GraphQLOperationNotAllowedException(operationType)
        }
        if (operationType == GraphQLOperationType.SUBSCRIPTION) {
            return GraphQLResponseEnvelope(
                errors = listOf(GraphQLError("Subscription operations require the streaming subscription API")),
            )
        }
        val executorFactory = when (prepared.operation.type) {
            com.steamstreet.graphkt.server.execution.OperationType.QUERY -> query
            com.steamstreet.graphkt.server.execution.OperationType.MUTATION -> mutation
            com.steamstreet.graphkt.server.execution.OperationType.SUBSCRIPTION -> error("Subscription execution uses subscribe")
        }
        if (executorFactory == null) {
            return GraphQLResponseEnvelope(
                errors = listOf(GraphQLError("The requested operation type is not configured")),
            )
        }
        return withGraphQLRequestScope { requestScope ->
            val context = requestScope.contextFactory()
            val errors = mutableListOf<GraphQLError>()
            val selection = buildRequestSelection(
                schema = schema,
                prepared = prepared,
                onInputErrors = errors::addAll,
                directiveExecutor = FieldDirectiveExecutor { field, block ->
                    executeDirectiveHandlers(context, field, directiveHandlers, block)
                },
            )

            val data = try {
                val resolver = executorFactory.create(context)
                when (prepared.operation.type) {
                    com.steamstreet.graphkt.server.execution.OperationType.QUERY -> resolveQueryFields(
                        selection = selection,
                        resolver = resolver,
                        maximumParallelism = executionPolicy.maximumQueryParallelism,
                    )

                    com.steamstreet.graphkt.server.execution.OperationType.MUTATION ->
                        resolveMutationFields(selection, resolver)

                    com.steamstreet.graphkt.server.execution.OperationType.SUBSCRIPTION -> error("Subscription executor is not configured")
                }
            } catch (failure: NonNullPropagationException) {
                null
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                errors += errorMapper.map(context, failure, emptyList())
                null
            }
            selection.takeResolverFailures().forEach { failure ->
                errors += errorMapper.map(context, failure.cause, failure.path)
            }

            GraphQLResponseEnvelope(
                data = data,
                errors = errors.takeIf { it.isNotEmpty() },
                kind = GraphQLResponseKind.EXECUTION_RESULT,
            )
        }
    }

    private fun subscribeRequest(
        request: GraphQLRequest,
        contextFactory: suspend GraphQLRequestScope.() -> Context,
    ): Flow<GraphQLResponseEnvelope> = flow {
        val preparation = GraphQLRequestPreparer(schema, limits).prepare(request)
        val prepared = preparation.operation
        if (prepared == null) {
            emit(
                GraphQLResponseEnvelope(
                    errors = preparation.errors,
                    kind = when (preparation.failureKind) {
                        RequestPreparationFailureKind.DOCUMENT -> GraphQLResponseKind.DOCUMENT_ERROR
                        RequestPreparationFailureKind.REQUEST, null -> GraphQLResponseKind.REQUEST_ERROR
                    },
                ),
            )
            return@flow
        }

        val operationType = prepared.operation.type.toPublicType()
        if (operationType != GraphQLOperationType.SUBSCRIPTION) {
            throw GraphQLOperationNotAllowedException(operationType)
        }
        val executorFactory = subscription
        if (executorFactory == null) {
            emit(GraphQLResponseEnvelope(errors = listOf(GraphQLError("The requested operation type is not configured"))))
            return@flow
        }

        withGraphQLRequestScope { requestScope ->
            val context = requestScope.contextFactory()
            val preparationErrors = mutableListOf<GraphQLError>()
            val selection = buildRequestSelection(
                schema = schema,
                prepared = prepared,
                onInputErrors = preparationErrors::addAll,
                directiveExecutor = FieldDirectiveExecutor { field, block ->
                    executeDirectiveHandlers(context, field, directiveHandlers, block)
                },
            )
            if (preparationErrors.isNotEmpty()) {
                emit(GraphQLResponseEnvelope(errors = preparationErrors))
                return@withGraphQLRequestScope
            }

            val rootSelection = selection.children.single()
            val source = try {
                executorFactory.create(context).subscribe(rootSelection)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                emit(
                    GraphQLResponseEnvelope(
                        errors = listOf(errorMapper.map(context, failure, rootSelection.path)),
                    ),
                )
                return@withGraphQLRequestScope
            }

            source.collect { event ->
                val errors = mutableListOf<GraphQLError>()
                val data = try {
                    JsonObject(mapOf(rootSelection.responseName to event.resolve()))
                } catch (failure: NonNullPropagationException) {
                    null
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    errors += errorMapper.map(context, failure, rootSelection.path)
                    null
                }
                selection.takeResolverFailures().forEach { failure ->
                    errors += errorMapper.map(context, failure.cause, failure.path)
                }
                emit(
                    GraphQLResponseEnvelope(
                        data = data,
                        errors = errors.takeIf { it.isNotEmpty() },
                        kind = GraphQLResponseKind.EXECUTION_RESULT,
                    ),
                )
            }
        }
    }
}

private suspend fun <Result> withGraphQLRequestScope(
    block: suspend (GraphQLRequestScope) -> Result,
): Result {
    val requestScope = DefaultGraphQLRequestScope(currentCoroutineContext())
    var completedNormally = false
    var requestFailure: Throwable? = null
    return try {
        block(requestScope).also { completedNormally = true }
    } catch (failure: Throwable) {
        requestFailure = failure
        throw failure
    } finally {
        val cleanupFailures = withContext(NonCancellable) { requestScope.close() }
        if (cleanupFailures.isNotEmpty()) {
            if (requestFailure != null) {
                cleanupFailures.forEach(requestFailure::addSuppressed)
            } else if (completedNormally) {
                throw GraphQLRequestCleanupException(cleanupFailures)
            }
        }
    }
}

private suspend fun <Context> executeDirectiveHandlers(
    context: Context,
    selection: RequestSelection,
    handlers: Map<String, GraphQLDirectiveHandler<Context>>,
    block: suspend () -> JsonElement,
): JsonElement {
    val activeHandlers = selection.directives.mapNotNull { directive ->
        handlers[directive.name]?.let { handler -> directive to handler }
    }

    suspend fun proceed(index: Int): JsonElement {
        if (index == activeHandlers.size) return block()
        val (directive, handler) = activeHandlers[index]
        var continued = false
        return handler.resolve(
            context = context,
            directive = directive,
            selection = selection,
            next = GraphQLDirectiveContinuation {
                check(!continued) { "A directive continuation can be used only once" }
                continued = true
                proceed(index + 1)
            },
        )
    }

    return proceed(0)
}

private suspend fun resolveMutationFields(
    selection: RequestSelection,
    resolver: GraphQLRootFieldResolver,
): JsonObject {
    val fields = selection.children.mapNotNull { child ->
        resolver.resolve(child)?.let { value -> child.responseName to value }
    }
    return JsonObject(fields.toMap())
}

private suspend fun resolveQueryFields(
    selection: RequestSelection,
    resolver: GraphQLRootFieldResolver,
    maximumParallelism: Int,
): JsonObject {
    val results = coroutineScope {
        val permits = Semaphore(maximumParallelism)
        selection.children.map { child ->
            async {
                permits.withPermit {
                    try {
                        RootFieldResult.Value(
                            resolver.resolve(child)?.let { value -> child.responseName to value },
                        )
                    } catch (_: NonNullPropagationException) {
                        RootFieldResult.NonNullFailure
                    }
                }
            }
        }.awaitAll()
    }
    if (results.any { it is RootFieldResult.NonNullFailure }) {
        throw NonNullPropagationException()
    }
    return JsonObject(
        results.mapNotNull { result -> (result as RootFieldResult.Value).field }.toMap(),
    )
}

private sealed interface RootFieldResult {
    data class Value(val field: Pair<String, JsonElement>?) : RootFieldResult
    data object NonNullFailure : RootFieldResult
}

private fun com.steamstreet.graphkt.server.execution.OperationType.toPublicType(): GraphQLOperationType = when (this) {
    com.steamstreet.graphkt.server.execution.OperationType.QUERY -> GraphQLOperationType.QUERY
    com.steamstreet.graphkt.server.execution.OperationType.MUTATION -> GraphQLOperationType.MUTATION
    com.steamstreet.graphkt.server.execution.OperationType.SUBSCRIPTION -> GraphQLOperationType.SUBSCRIPTION
}
