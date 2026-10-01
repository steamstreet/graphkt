package com.steamstreet.graphkt.server

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.GraphQLPathSegment
import com.steamstreet.graphkt.server.execution.Argument
import com.steamstreet.graphkt.server.execution.FieldSelection
import com.steamstreet.graphkt.server.execution.FragmentSpread
import com.steamstreet.graphkt.server.execution.GraphQLDocumentParser
import com.steamstreet.graphkt.server.execution.InlineFragment
import com.steamstreet.graphkt.server.execution.Selection
import com.steamstreet.graphkt.server.execution.SourceLocation
import com.steamstreet.graphkt.server.execution.Value
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val ARGUMENT_VARIABLE_PREFIX = "__graphkt_argument_"

/** The location given to the synthesized root field, which has no place in any document. */
private val SYNTHESIZED = SourceLocation(offset = 0, line = 1, column = 1)

/** Builds the selection that [RequestSelection.forRootField] returns. */
internal fun buildRootFieldSelection(
    fieldName: String,
    selectionSet: String?,
    arguments: Map<String, JsonElement>,
    variables: Map<String, JsonElement>,
    errors: MutableList<GraphQLError>,
    errorFactory: GraphQLResolverErrorFactory,
): RequestSelection {
    val selections = if (selectionSet.isNullOrBlank()) emptyList() else parseSelectionSet(selectionSet)
    // Each argument is passed through a variable of its own, so that resolved JSON values reach the
    // resolver unchanged instead of round-tripping through GraphQL literals.
    val field = FieldSelection(
        alias = null,
        name = fieldName,
        arguments = arguments.keys.map { name ->
            Argument(name, Value.Variable("$ARGUMENT_VARIABLE_PREFIX$name"), SYNTHESIZED)
        },
        directives = emptyList(),
        selections = selections,
        location = SYNTHESIZED,
    )
    return RootFieldRequestSelection(
        scope = RootFieldScope(
            variables = variables + arguments.mapKeys { (name, _) -> "$ARGUMENT_VARIABLE_PREFIX$name" },
            errors = errors,
            errorFactory = errorFactory,
        ),
        parent = null,
        node = null,
        selections = listOf(field),
        typeName = null,
    )
}

private fun parseSelectionSet(selectionSet: String): List<Selection> {
    val document = GraphQLDocumentParser().parse(selectionSet)
    require(document.operations.size == 1) {
        "A root field's selection set must be a single selection set, such as '{ id name }'"
    }
    val selections = document.operations.single().selections
    rejectFragmentSpreads(selections)
    return selections
}

private fun rejectFragmentSpreads(selections: List<Selection>) {
    selections.forEach { selection ->
        when (selection) {
            is FieldSelection -> rejectFragmentSpreads(selection.selections)
            is InlineFragment -> rejectFragmentSpreads(selection.selections)
            is FragmentSpread -> throw IllegalArgumentException(
                "The selection set spreads the named fragment '${selection.name}' at " +
                    "${selection.location.line}:${selection.location.column}. AppSync's selectionSetGraphQL " +
                    "omits fragment definitions, so the fields a named fragment selects are unknown. " +
                    "Select the fields directly or in an inline fragment, such as '... on Type { id }'.",
            )
        }
    }
}

private class RootFieldScope(
    val variables: Map<String, JsonElement>,
    val errors: MutableList<GraphQLError>,
    val errorFactory: GraphQLResolverErrorFactory,
)

/**
 * One node of a [RequestSelection.forRootField] selection: the root selection set when [node] is
 * null, and otherwise a field. Fields are keyed by field name, and paths use response names.
 */
private class RootFieldRequestSelection(
    private val scope: RootFieldScope,
    private val parent: RootFieldRequestSelection?,
    private val node: FieldSelection?,
    private val selections: List<Selection>,
    override val typeName: String?,
    private val pathOverride: List<GraphQLPathSegment>? = null,
) : RequestSelection {
    override val name: String
        get() = node?.name ?: throw IllegalStateException("The root selection set is not a field")

    override val responseName: String
        get() = name

    override val path: List<GraphQLPathSegment>
        get() = pathOverride
            ?: (parent?.path.orEmpty() + listOfNotNull(node?.let { GraphQLPathSegment.Field(it.responseName) }))

    override val children: List<RequestSelection> by lazy {
        buildList { addFields(selections, typeName = null) }
    }

    private fun MutableList<RequestSelection>.addFields(selections: List<Selection>, typeName: String?) {
        selections.forEach { selection ->
            when (selection) {
                is FieldSelection -> add(
                    RootFieldRequestSelection(
                        scope = scope,
                        parent = this@RootFieldRequestSelection,
                        node = selection,
                        selections = selection.selections,
                        typeName = typeName,
                    ),
                )

                is InlineFragment -> addFields(selection.selections, selection.typeCondition ?: typeName)
                is FragmentSpread -> error("Named fragment spreads are rejected when the selection is built")
            }
        }
    }

    override val parameters: Map<String, String>
        get() = node?.arguments.orEmpty().associate { it.name to it.value.toJson().toString() }

    override fun inputParameter(key: String): JsonElement =
        node?.arguments?.find { it.name == key }?.value?.toJson() ?: JsonNull

    override fun variable(key: String): JsonElement = scope.variables[key] ?: JsonNull

    override fun forIndex(index: Int): RequestSelection = RootFieldRequestSelection(
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
        is Value.Variable -> scope.variables[name] ?: JsonNull
        is Value.BooleanValue -> JsonPrimitive(value)
        is Value.StringValue -> JsonPrimitive(value)
        // The lexer has already checked the number's syntax. A value too large for a Long keeps its
        // literal digits, as GraphQL Java's BigInteger does.
        is Value.IntValue -> value.toLongOrNull()?.let { JsonPrimitive(it) } ?: Json.parseToJsonElement(value)
        is Value.FloatValue -> Json.parseToJsonElement(value)
        is Value.EnumValue -> JsonPrimitive(value)
        is Value.ListValue -> JsonArray(values.map { it.toJson() })
        is Value.ObjectValue -> JsonObject(fields.associate { it.name to it.value.toJson() })
        Value.NullValue -> JsonNull
    }
}
