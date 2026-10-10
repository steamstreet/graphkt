package com.steamstreet.graphkt.server

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.GraphQLPathSegment
import com.steamstreet.graphkt.server.execution.ExecutableDocument
import com.steamstreet.graphkt.server.execution.FieldSelection
import com.steamstreet.graphkt.server.execution.FragmentDefinition
import com.steamstreet.graphkt.server.execution.FragmentSpread
import com.steamstreet.graphkt.server.execution.GraphQLDocumentParser
import com.steamstreet.graphkt.server.execution.InlineFragment
import com.steamstreet.graphkt.server.execution.OperationDefinition
import com.steamstreet.graphkt.server.execution.OperationType
import com.steamstreet.graphkt.server.execution.Selection
import com.steamstreet.graphkt.server.execution.Value
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The kind of a GraphQL operation. */
public enum class RequestOperationType {
    QUERY,
    MUTATION,
    SUBSCRIPTION,
}

/**
 * A parsed GraphQL operation: its [type] and the root [selection] to pass to a generated root
 * `gqlSelect`. See [RequestSelection.forOperation].
 */
public class RequestOperation(
    public val type: RequestOperationType,
    public val selection: RequestSelection,
) {
    public companion object {
        /**
         * Parses [query] and builds the root selection of one of its operations, as
         * [RequestSelection.forOperation] does, but also reports the operation's type so that a
         * caller can choose the query or the mutation root.
         *
         * @throws IllegalArgumentException when [query] cannot be parsed, or when it holds no
         * operation to select.
         */
        public fun parse(
            query: String,
            variables: Map<String, JsonElement>?,
            errors: MutableList<GraphQLError>,
            errorFactory: GraphQLResolverErrorFactory = GraphQLResolverErrorFactory.Generic,
            operationName: String? = null,
        ): RequestOperation = buildOperation(query, variables, errors, errorFactory, operationName)
    }
}

internal fun buildOperation(
    query: String,
    variables: Map<String, JsonElement>?,
    errors: MutableList<GraphQLError>,
    errorFactory: GraphQLResolverErrorFactory,
    operationName: String?,
): RequestOperation {
    val document = GraphQLDocumentParser().parse(query)
    val operation = document.operationFor(operationName)
    val scope = OperationScope(
        variables = variables.orEmpty(),
        errors = errors,
        errorFactory = errorFactory,
        fragments = document.fragments.associateBy { it.name },
    )
    return RequestOperation(
        type = when (operation.type) {
            OperationType.QUERY -> RequestOperationType.QUERY
            OperationType.MUTATION -> RequestOperationType.MUTATION
            OperationType.SUBSCRIPTION -> RequestOperationType.SUBSCRIPTION
        },
        selection = DocumentRequestSelection(
            scope = scope,
            parent = null,
            node = null,
            selections = operation.selections,
            typeName = null,
        ),
    )
}

/**
 * Chooses the operation to run. Without a name this follows the JVM `parseGraphQLOperation`: the
 * document's first definition must be an operation, and it is the one used, even when the document
 * holds others.
 */
private fun ExecutableDocument.operationFor(operationName: String?): OperationDefinition {
    if (operationName != null) return selectOperation(operationName)
    val first = operations.firstOrNull()
    val firstFragmentOffset = fragments.minOfOrNull { it.location.offset }
    require(first != null && (firstFragmentOffset == null || first.location.offset < firstFragmentOffset)) {
        "Operation was not found"
    }
    return first
}

private class OperationScope(
    val variables: Map<String, JsonElement>,
    val errors: MutableList<GraphQLError>,
    val errorFactory: GraphQLResolverErrorFactory,
    val fragments: Map<String, FragmentDefinition>,
)

/**
 * One node of a [RequestSelection.forOperation] selection: the operation's root selection set when
 * [node] is null, and otherwise a field. Fields are keyed by their response names, which are their
 * aliases when the document gives one.
 */
private class DocumentRequestSelection(
    private val scope: OperationScope,
    private val parent: DocumentRequestSelection?,
    private val node: FieldSelection?,
    private val selections: List<Selection>,
    override val typeName: String?,
    private val pathOverride: List<GraphQLPathSegment>? = null,
) : RequestSelection {
    override val name: String
        get() = node?.name ?: throw IllegalStateException("Not a named node")

    override val responseName: String
        get() = node?.responseName ?: throw IllegalStateException("Not a named node")

    override val path: List<GraphQLPathSegment>
        get() = pathOverride
            ?: (parent?.path.orEmpty() + listOfNotNull(node?.let { GraphQLPathSegment.Field(it.responseName) }))

    override val children: List<RequestSelection> by lazy {
        buildList { addFields(selections, typeName = null, spreading = emptyList()) }
    }

    /**
     * Adds the fields of [selections]. An inline fragment's type condition becomes the type name of
     * the fields it selects, and a field outside any fragment has none.
     */
    private fun MutableList<RequestSelection>.addFields(
        selections: List<Selection>,
        typeName: String?,
        spreading: List<String>,
    ) {
        selections.forEach { selection ->
            when (selection) {
                is FieldSelection -> add(
                    DocumentRequestSelection(
                        scope = scope,
                        parent = this@DocumentRequestSelection,
                        node = selection,
                        selections = selection.selections,
                        typeName = typeName,
                    ),
                )

                is InlineFragment -> addFields(selection.selections, selection.typeCondition ?: typeName, spreading)
                is FragmentSpread -> {
                    val fragment = scope.fragments[selection.name] ?: throw IllegalArgumentException(
                        "Unknown fragment '${selection.name}' at ${selection.location.line}:${selection.location.column}",
                    )
                    require(fragment.name !in spreading) {
                        "The fragment '${fragment.name}' spreads itself at " +
                            "${selection.location.line}:${selection.location.column}"
                    }
                    addFields(fragment.selections, fragment.typeCondition, spreading + fragment.name)
                }
            }
        }
    }

    // Arguments are rendered as the JVM does: each value as the string form of its JSON.
    override val parameters: Map<String, String>
        get() = node?.arguments.orEmpty().associate { it.name to it.value.toJson().toString() }

    override fun inputParameter(key: String): JsonElement {
        val field = node ?: throw IllegalStateException("The root selection set has no arguments")
        return field.arguments.find { it.name == key }?.value?.toJson() ?: JsonNull
    }

    override fun variable(key: String): JsonElement = scope.variables[key] ?: JsonNull

    override fun forIndex(index: Int): RequestSelection = DocumentRequestSelection(
        scope = scope,
        parent = parent,
        node = node,
        selections = selections,
        typeName = typeName,
        pathOverride = path + GraphQLPathSegment.Index(index),
    )

    override fun error(t: Throwable) {
        scope.errors.add(scope.errorFactory.create(t, path))
    }

    private fun Value.toJson(): JsonElement = when (this) {
        // A variable is looked up by name only; a default in its definition is not applied.
        is Value.Variable -> scope.variables[name] ?: JsonNull
        is Value.BooleanValue -> JsonPrimitive(value)
        is Value.StringValue -> JsonPrimitive(value)
        // The lexer has already checked the number's syntax. GraphQL Java reads the literals as
        // BigInteger and BigDecimal, and the JVM selection renders those with their toString.
        is Value.IntValue -> value.toLongOrNull()?.let { JsonPrimitive(it) } ?: Json.parseToJsonElement(value)
        is Value.FloatValue -> Json.parseToJsonElement(bigDecimalString(value))
        is Value.EnumValue -> JsonPrimitive(value)
        is Value.ListValue -> JsonArray(values.map { it.toJson() })
        is Value.ObjectValue -> JsonObject(fields.associate { it.name to it.value.toJson() })
        Value.NullValue -> JsonNull
    }
}

/**
 * Renders a GraphQL float literal as `java.math.BigDecimal(literal).toString()` does: the digits
 * keep their scale, and a value that is too small or too large for plain notation is written in
 * scientific notation, such as `1E+3` for `1e3`.
 */
internal fun bigDecimalString(literal: String): String {
    val negative = literal.startsWith("-")
    val unsigned = literal.removePrefix("-")
    val exponentAt = unsigned.indexOfFirst { it == 'e' || it == 'E' }
    val mantissa = if (exponentAt < 0) unsigned else unsigned.substring(0, exponentAt)
    val exponent = if (exponentAt < 0) 0L else unsigned.substring(exponentAt + 1).removePrefix("+").toLong()
    val point = mantissa.indexOf('.')
    val fraction = if (point < 0) "" else mantissa.substring(point + 1)
    val allDigits = mantissa.replace(".", "")
    val scale = fraction.length - exponent
    val digits = allDigits.trimStart('0').ifEmpty { "0" }
    val isZero = digits == "0"

    val body = when {
        scale == 0L -> digits
        else -> {
            val adjusted = -scale + (digits.length - 1)
            if (scale > 0 && adjusted >= -6) {
                val padding = scale - digits.length
                if (padding >= 0) {
                    "0." + "0".repeat(padding.toInt()) + digits
                } else {
                    val split = (-padding).toInt()
                    digits.substring(0, split) + "." + digits.substring(split)
                }
            } else {
                buildString {
                    append(digits[0])
                    if (digits.length > 1) append('.').append(digits, 1, digits.length)
                    if (adjusted != 0L) {
                        append('E')
                        if (adjusted > 0) append('+')
                        append(adjusted)
                    }
                }
            }
        }
    }
    return if (negative && !isZero) "-$body" else body
}
