package com.steamstreet.graphkt.server.execution

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.GraphQLPathSegment
import com.steamstreet.graphkt.server.ExecutionSelectionState
import com.steamstreet.graphkt.server.FieldDirectiveExecutor
import com.steamstreet.graphkt.server.GraphQLAppliedDirective
import com.steamstreet.graphkt.server.RequestSelection
import com.steamstreet.graphkt.server.ResolverFailure
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.coroutines.cancellation.CancellationException

/** Builds the request-scoped tree consumed by generated resolver adapters. */
internal fun buildRequestSelection(
    schema: GraphQLSchemaDefinition,
    prepared: PreparedOperation,
    onInputErrors: (List<GraphQLError>) -> Unit,
    directiveExecutor: FieldDirectiveExecutor,
): RequestSelection {
    val rootType = schema.rootType(prepared.operation.type)
        ?: error("A prepared operation must have a root type")
    val builder = SelectionTreeBuilder(schema, prepared, onInputErrors, directiveExecutor)
    return ExecutionRequestSelection(
        name = rootType.name,
        responseName = rootType.name,
        typeName = rootType.name,
        path = emptyList(),
        arguments = emptyMap(),
        variables = prepared.variables,
        children = builder.buildFields(
            parentTypeName = rootType.name,
            selections = prepared.operation.selections,
            path = emptyList(),
        ),
        applicableTypes = null,
        directives = emptyList(),
        directiveExecutor = directiveExecutor,
        hasPreExecutionError = false,
        resolverFailures = mutableListOf(),
    )
}

private class SelectionTreeBuilder(
    private val schema: GraphQLSchemaDefinition,
    private val prepared: PreparedOperation,
    private val onInputErrors: (List<GraphQLError>) -> Unit,
    private val directiveExecutor: FieldDirectiveExecutor,
) {
    private val fragments = prepared.document.fragments.associateBy { it.name }
    private val coercer = GraphQLInputCoercer(schema)

    fun buildFields(
        parentTypeName: String,
        selections: List<Selection>,
        path: List<GraphQLPathSegment>,
    ): List<RequestSelection> {
        val possibleParentTypes = schema.possibleObjectTypes(parentTypeName)
        val occurrences = mutableListOf<FieldOccurrence>()
        collectFields(
            parentTypeName = parentTypeName,
            selections = selections,
            applicableTypes = possibleParentTypes,
            occurrences = occurrences,
        )

        return occurrences
            .groupBy(FieldOccurrence::responseName)
            .flatMap { (_, responseOccurrences) ->
                partitionByApplicableOccurrences(responseOccurrences, possibleParentTypes)
                    .map { partition -> buildField(parentTypeName, partition, possibleParentTypes, path) }
            }
    }

    private fun collectFields(
        parentTypeName: String,
        selections: List<Selection>,
        applicableTypes: Set<String>,
        occurrences: MutableList<FieldOccurrence>,
    ) {
        selections.forEach { selection ->
            when (selection) {
                is FieldSelection -> if (selection.directives.shouldInclude()) {
                    occurrences += FieldOccurrence(parentTypeName, selection, applicableTypes)
                }

                is FragmentSpread -> if (selection.directives.shouldInclude()) {
                    val fragment = fragments.getValue(selection.name)
                    if (fragment.directives.shouldInclude()) {
                        val narrowedTypes = applicableTypes intersect schema.possibleObjectTypes(fragment.typeCondition)
                        if (narrowedTypes.isNotEmpty()) {
                            collectFields(fragment.typeCondition, fragment.selections, narrowedTypes, occurrences)
                        }
                    }
                }

                is InlineFragment -> if (selection.directives.shouldInclude()) {
                    val targetType = selection.typeCondition ?: parentTypeName
                    val narrowedTypes = applicableTypes intersect schema.possibleObjectTypes(targetType)
                    if (narrowedTypes.isNotEmpty()) {
                        collectFields(targetType, selection.selections, narrowedTypes, occurrences)
                    }
                }
            }
        }
    }

    private fun partitionByApplicableOccurrences(
        occurrences: List<FieldOccurrence>,
        possibleParentTypes: Set<String>,
    ): List<FieldPartition> {
        val indexed = occurrences.withIndex().toList()
        return possibleParentTypes
            .mapNotNull { concreteType ->
                val active = indexed.filter { concreteType in it.value.applicableTypes }
                active.takeIf { it.isNotEmpty() }?.let { concreteType to it }
            }
            .groupBy(
                keySelector = { (_, active) -> active.map { it.index } },
                valueTransform = { it },
            )
            .values
            .map { concretePartitions ->
                val active = concretePartitions.first().second.map { it.value }
                FieldPartition(
                    occurrences = active,
                    applicableTypes = concretePartitions.mapTo(linkedSetOf()) { it.first },
                )
            }
    }

    private fun buildField(
        parentTypeName: String,
        partition: FieldPartition,
        possibleParentTypes: Set<String>,
        parentPath: List<GraphQLPathSegment>,
    ): RequestSelection {
        val first = partition.occurrences.first()
        val selection = first.selection
        val responsePath = parentPath + GraphQLPathSegment.Field(selection.responseName)
        val field = field(first.parentTypeName, selection)
        val coercion = coercer.coerceArguments(field, selection.arguments, prepared.variables)
        if (!coercion.isValid) {
            onInputErrors(
                coercion.errors.map { error ->
                    GraphQLError(
                        message = error.message,
                        locations = error.locations,
                        path = responsePath,
                        extensions = error.extensions,
                    )
                },
            )
        }
        val appliedDirectives = mutableListOf<GraphQLAppliedDirective>()
        var hasDirectiveErrors = false
        partition.occurrences.forEach { occurrence ->
            occurrence.selection.directives.forEach { directive ->
                val definition = schema.directive(directive.name)
                    ?: error("A prepared directive must exist in the schema")
                val directiveCoercion = coercer.coerceDirectiveArguments(definition, directive, prepared.variables)
                if (!directiveCoercion.isValid) {
                    hasDirectiveErrors = true
                    onInputErrors(
                        directiveCoercion.errors.map { error ->
                            GraphQLError(
                                message = error.message,
                                locations = error.locations,
                                path = responsePath,
                                extensions = error.extensions,
                            )
                        },
                    )
                }
                appliedDirectives += GraphQLAppliedDirective(directive.name, directiveCoercion.values)
            }
        }

        val resultTypeName = field.type.namedType().name
        val childSelections = partition.occurrences.flatMap { it.selection.selections }
        val children = if (childSelections.isEmpty()) {
            emptyList()
        } else {
            buildFields(resultTypeName, childSelections, responsePath)
        }
        val applicableTypes = partition.applicableTypes.takeUnless { it == possibleParentTypes }

        return ExecutionRequestSelection(
            name = selection.name,
            responseName = selection.responseName,
            typeName = first.parentTypeName.takeUnless { it == parentTypeName },
            path = responsePath,
            arguments = coercion.values,
            variables = prepared.variables,
            children = children,
            applicableTypes = applicableTypes,
            directives = appliedDirectives,
            directiveExecutor = directiveExecutor,
            hasPreExecutionError = !coercion.isValid || hasDirectiveErrors,
            resolverFailures = mutableListOf(),
        )
    }

    private fun field(parentTypeName: String, selection: FieldSelection): GraphQLFieldDefinition {
        if (selection.name == "__typename") {
            return GraphQLFieldDefinition(
                name = "__typename",
                type = GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("String")),
            )
        }
        return when (val parentType = schema.type(parentTypeName)) {
            is GraphQLObjectType -> parentType.field(selection.name)
            is GraphQLInterfaceType -> parentType.field(selection.name)
            else -> null
        } ?: error("A prepared field must exist on its parent type")
    }

    private fun List<Directive>.shouldInclude(): Boolean {
        forEach { directive ->
            when (directive.name) {
                "skip" -> if (directive.arguments.single { it.name == "if" }.value.booleanValue()) return false
                "include" -> if (!directive.arguments.single { it.name == "if" }.value.booleanValue()) return false
            }
        }
        return true
    }

    private fun Value.booleanValue(): Boolean = when (this) {
        is Value.BooleanValue -> value
        is Value.Variable -> (prepared.variables[name] as? JsonPrimitive)?.booleanOrNull
            ?: error("A prepared directive condition must be a Boolean")

        else -> error("A prepared directive condition must be a Boolean")
    }
}

private data class FieldOccurrence(
    val parentTypeName: String,
    val selection: FieldSelection,
    val applicableTypes: Set<String>,
) {
    val responseName: String get() = selection.responseName
}

private data class FieldPartition(
    val occurrences: List<FieldOccurrence>,
    val applicableTypes: Set<String>,
)

private class ExecutionRequestSelection(
    override val name: String,
    override val responseName: String,
    override val typeName: String?,
    override val path: List<GraphQLPathSegment>,
    private val arguments: Map<String, JsonElement>,
    private val variables: Map<String, JsonElement>,
    override val children: List<RequestSelection>,
    private val applicableTypes: Set<String>?,
    override val directives: List<GraphQLAppliedDirective>,
    private val directiveExecutor: FieldDirectiveExecutor,
    override val hasPreExecutionError: Boolean,
    override val resolverFailures: MutableList<ResolverFailure>,
) : RequestSelection, ExecutionSelectionState {
    override val parameters: Map<String, String> = arguments.mapValues { (_, value) -> value.toString() }

    override fun inputParameter(key: String): JsonElement = arguments[key] ?: JsonNull

    override fun variable(key: String): JsonElement = variables[key] ?: JsonNull

    override fun forIndex(index: Int): RequestSelection = rebase(path + GraphQLPathSegment.Index(index))

    override fun appliesTo(concreteTypeName: String): Boolean =
        applicableTypes == null || concreteTypeName in applicableTypes

    override suspend fun resolveWithDirectives(block: suspend () -> JsonElement): JsonElement =
        directiveExecutor.execute(this, block)

    override fun error(t: Throwable) {
        if (t is CancellationException) throw t
        resolverFailures += ResolverFailure(t, path)
    }

    private fun rebase(newPath: List<GraphQLPathSegment>): ExecutionRequestSelection = ExecutionRequestSelection(
        name = name,
        responseName = responseName,
        typeName = typeName,
        path = newPath,
        arguments = arguments,
        variables = variables,
        children = children.map { child ->
            (child as ExecutionRequestSelection).rebase(newPath + GraphQLPathSegment.Field(child.responseName))
        },
        applicableTypes = applicableTypes,
        directives = directives,
        directiveExecutor = directiveExecutor,
        hasPreExecutionError = hasPreExecutionError,
        resolverFailures = resolverFailures,
    )
}
