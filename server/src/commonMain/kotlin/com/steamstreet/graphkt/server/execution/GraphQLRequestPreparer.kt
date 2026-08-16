package com.steamstreet.graphkt.server.execution

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.GraphQLRequest
import com.steamstreet.graphkt.Location
import kotlinx.serialization.json.JsonElement

/** A parsed, validated, and coerced operation that is safe to pass to the execution engine. */
internal data class PreparedOperation(
    val document: ExecutableDocument,
    val operation: OperationDefinition,
    val variables: Map<String, JsonElement>,
)

internal data class RequestPreparationResult(
    val operation: PreparedOperation?,
    val errors: List<GraphQLError>,
    val failureKind: RequestPreparationFailureKind? = null,
) {
    val isValid: Boolean get() = operation != null && errors.isEmpty()
}

internal enum class RequestPreparationFailureKind {
    DOCUMENT,
    REQUEST,
}

/** Applies all request checks that must complete before resolver construction. */
internal class GraphQLRequestPreparer(
    private val schema: GraphQLSchemaDefinition,
    private val limits: GraphQLDocumentLimits = GraphQLDocumentLimits(),
) {
    fun prepare(request: GraphQLRequest): RequestPreparationResult {
        val variableBytes = request.variables?.toString()?.encodeToByteArray()?.size ?: 0
        if (variableBytes > limits.maxVariableBytes) {
            return RequestPreparationResult(
                operation = null,
                errors = listOf(
                    GraphQLError("Variables exceed the ${limits.maxVariableBytes}-byte limit"),
                ),
                failureKind = RequestPreparationFailureKind.REQUEST,
            )
        }

        val document = try {
            GraphQLDocumentParser(limits).parse(request.query)
        } catch (exception: GraphQLDocumentException) {
            return RequestPreparationResult(
                operation = null,
                errors = listOf(
                    GraphQLError(
                        message = exception.message,
                        locations = exception.location?.let { listOf(Location(it.line, it.column)) },
                    ),
                ),
                failureKind = RequestPreparationFailureKind.DOCUMENT,
            )
        }

        val validation = GraphQLDocumentValidator(schema, limits).validate(document, request.operationName)
        if (!validation.isValid) {
            return RequestPreparationResult(
                operation = null,
                errors = validation.errors,
                failureKind = RequestPreparationFailureKind.REQUEST,
            )
        }

        val operation = validation.operation ?: error("A valid result must contain an operation")
        val variables = GraphQLInputCoercer(schema).coerceVariables(operation, request.variables)
        if (!variables.isValid) {
            return RequestPreparationResult(
                operation = null,
                errors = variables.errors,
                failureKind = RequestPreparationFailureKind.REQUEST,
            )
        }

        return RequestPreparationResult(
            operation = PreparedOperation(document, operation, variables.values),
            errors = emptyList(),
        )
    }
}
