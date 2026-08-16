package com.steamstreet.graphkt.server.execution

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.Location

/** Validates field conflicts after it expands fragments and inline fragments. */
internal class GraphQLFieldMergingValidator(
    private val schema: GraphQLSchemaDefinition,
    private val fragments: Map<String, FragmentDefinition>,
) {
    private val responseShapeChecks = mutableSetOf<List<FieldIdentity>>()
    private val commonParentChecks = mutableSetOf<List<FieldIdentity>>()
    private val reportedConflicts = mutableSetOf<ConflictIdentity>()

    fun validate(
        parentTypeName: String,
        selections: List<Selection>,
        errors: MutableList<GraphQLError>,
    ) {
        val fields = collectFields(parentTypeName, selections)
        validateResponseShapes(fields, emptyList(), errors)
        validateCommonParents(fields, emptyList(), errors)
    }

    private fun validateResponseShapes(
        fieldsByResponseName: Map<String, List<FieldAndType>>,
        path: List<String>,
        errors: MutableList<GraphQLError>,
    ) {
        fieldsByResponseName.forEach { (responseName, fields) ->
            val currentPath = path + responseName
            if (!responseShapeChecks.add(fields.identity())) return@forEach
            val first = fields.firstOrNull { it.type != null } ?: return@forEach
            val firstType = first.type ?: return@forEach
            val conflict = fields.firstOrNull { field ->
                field !== first && field.type?.let { !sameResponseShape(firstType, it) } == true
            }
            if (conflict != null) {
                report(
                    path = currentPath,
                    reason = "they return incompatible types '${firstType.render()}' and '${conflict.type?.render()}'",
                    first = first,
                    second = conflict,
                    errors = errors,
                )
                return@forEach
            }
            validateResponseShapes(mergeSubSelections(fields), currentPath, errors)
        }
    }

    private fun validateCommonParents(
        fieldsByResponseName: Map<String, List<FieldAndType>>,
        path: List<String>,
        errors: MutableList<GraphQLError>,
    ) {
        fieldsByResponseName.forEach { (responseName, fields) ->
            val currentPath = path + responseName
            groupByCommonParents(fields).forEach { group ->
                if (!commonParentChecks.add(group.identity())) return@forEach
                val first = group.firstOrNull() ?: return@forEach
                val conflict = group.drop(1).firstOrNull { field ->
                    field.selection.name != first.selection.name ||
                        !sameArguments(field.selection.arguments, first.selection.arguments)
                }
                if (conflict != null) {
                    val reason = if (conflict.selection.name != first.selection.name) {
                        "they select '${first.selection.name}' and '${conflict.selection.name}'"
                    } else {
                        "they use different arguments"
                    }
                    report(currentPath, reason, first, conflict, errors)
                    return@forEach
                }
                validateCommonParents(mergeSubSelections(group), currentPath, errors)
            }
        }
    }

    private fun collectFields(
        parentTypeName: String,
        selections: List<Selection>,
    ): Map<String, List<FieldAndType>> {
        val fields = linkedMapOf<String, LinkedHashMap<Int, FieldAndType>>()
        collectFields(parentTypeName, selections, mutableSetOf(), fields)
        return fields.mapValues { (_, values) -> values.values.toList() }
    }

    private fun collectFields(
        parentTypeName: String,
        selections: List<Selection>,
        visitedFragments: MutableSet<String>,
        fields: MutableMap<String, LinkedHashMap<Int, FieldAndType>>,
    ) {
        selections.forEach { selection ->
            when (selection) {
                is FieldSelection -> {
                    val type = schema.field(parentTypeName, selection)?.type
                    val responseFields = fields.getOrPut(selection.responseName) { linkedMapOf() }
                    if (selection.location.offset !in responseFields) {
                        responseFields[selection.location.offset] = FieldAndType(selection, type, parentTypeName)
                    }
                }

                is FragmentSpread -> {
                    val fragment = fragments[selection.name] ?: return@forEach
                    if (visitedFragments.add(fragment.name)) {
                        collectFields(fragment.typeCondition, fragment.selections, visitedFragments, fields)
                    }
                }

                is InlineFragment -> collectFields(
                    selection.typeCondition ?: parentTypeName,
                    selection.selections,
                    visitedFragments,
                    fields,
                )
            }
        }
    }

    private fun mergeSubSelections(fields: List<FieldAndType>): Map<String, List<FieldAndType>> {
        val merged = linkedMapOf<String, LinkedHashMap<Int, FieldAndType>>()
        fields.forEach { field ->
            val typeName = field.type?.namedType()?.name ?: return@forEach
            if (field.selection.selections.isNotEmpty()) {
                collectFields(
                    parentTypeName = typeName,
                    selections = field.selection.selections,
                    visitedFragments = mutableSetOf(),
                    fields = merged,
                )
            }
        }
        return merged.mapValues { (_, values) -> values.values.toList() }
    }

    private fun groupByCommonParents(fields: List<FieldAndType>): List<List<FieldAndType>> {
        val abstractFields = fields.filter { schema.type(it.parentTypeName) !is GraphQLObjectType }
        val concreteGroups = fields
            .filter { schema.type(it.parentTypeName) is GraphQLObjectType }
            .groupBy(FieldAndType::parentTypeName)
            .values
        return if (concreteGroups.isEmpty()) {
            listOf(abstractFields)
        } else {
            concreteGroups.map { concreteFields -> concreteFields + abstractFields }
        }
    }

    private fun sameResponseShape(first: GraphQLTypeRef, second: GraphQLTypeRef): Boolean = when {
        first is GraphQLTypeRef.NonNull || second is GraphQLTypeRef.NonNull ->
            first is GraphQLTypeRef.NonNull && second is GraphQLTypeRef.NonNull &&
                sameResponseShape(first.value, second.value)

        first is GraphQLTypeRef.ListType || second is GraphQLTypeRef.ListType ->
            first is GraphQLTypeRef.ListType && second is GraphQLTypeRef.ListType &&
                sameResponseShape(first.element, second.element)

        else -> {
            val firstName = (first as GraphQLTypeRef.Named).name
            val secondName = (second as GraphQLTypeRef.Named).name
            val firstType = schema.type(firstName)
            val secondType = schema.type(secondName)
            if (firstType?.isLeafType() == true || secondType?.isLeafType() == true) {
                firstName == secondName
            } else {
                true
            }
        }
    }

    private fun sameArguments(first: List<Argument>, second: List<Argument>): Boolean {
        if (first.size != second.size) return false
        return first.all { argument ->
            second.singleOrNull { it.name == argument.name }?.value?.let { value ->
                sameValue(argument.value, value)
            } == true
        }
    }

    private fun sameValue(first: Value, second: Value): Boolean = when {
        first is Value.Variable && second is Value.Variable -> first.name == second.name
        first is Value.IntValue && second is Value.IntValue -> first.value == second.value
        first is Value.FloatValue && second is Value.FloatValue -> first.value == second.value
        first is Value.StringValue && second is Value.StringValue -> first.value == second.value
        first is Value.BooleanValue && second is Value.BooleanValue -> first.value == second.value
        first is Value.NullValue && second is Value.NullValue -> true
        first is Value.EnumValue && second is Value.EnumValue -> first.value == second.value
        first is Value.ListValue && second is Value.ListValue ->
            first.values.size == second.values.size &&
                first.values.zip(second.values).all { (firstItem, secondItem) -> sameValue(firstItem, secondItem) }

        first is Value.ObjectValue && second is Value.ObjectValue ->
            first.fields.size == second.fields.size && first.fields.all { firstField ->
                second.fields.singleOrNull { it.name == firstField.name }?.value?.let { secondValue ->
                    sameValue(firstField.value, secondValue)
                } == true
            }

        else -> false
    }

    private fun report(
        path: List<String>,
        reason: String,
        first: FieldAndType,
        second: FieldAndType,
        errors: MutableList<GraphQLError>,
    ) {
        val identity = ConflictIdentity(
            minOf(first.selection.location.offset, second.selection.location.offset),
            maxOf(first.selection.location.offset, second.selection.location.offset),
            path,
        )
        if (!reportedConflicts.add(identity)) return
        errors += GraphQLError(
            message = "Fields named '${path.joinToString(".")}' conflict because $reason",
            locations = listOf(first.selection.location, second.selection.location).map { location ->
                Location(location.line, location.column)
            },
        )
    }

    private fun GraphQLSchemaDefinition.field(
        parentTypeName: String,
        selection: FieldSelection,
    ): GraphQLFieldDefinition? {
        if (selection.name == "__typename") {
            return GraphQLFieldDefinition(
                "__typename",
                GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("String")),
            )
        }
        return when (val parentType = type(parentTypeName)) {
            is GraphQLObjectType -> parentType.field(selection.name)
            is GraphQLInterfaceType -> parentType.field(selection.name)
            else -> null
        }
    }
}

private class FieldAndType(
    val selection: FieldSelection,
    val type: GraphQLTypeRef?,
    val parentTypeName: String,
)

private data class FieldIdentity(
    val offset: Int,
    val parentTypeName: String,
)

private data class ConflictIdentity(
    val firstOffset: Int,
    val secondOffset: Int,
    val path: List<String>,
)

private fun List<FieldAndType>.identity(): List<FieldIdentity> = map { field ->
    FieldIdentity(field.selection.location.offset, field.parentTypeName)
}.sortedWith(compareBy(FieldIdentity::offset, FieldIdentity::parentTypeName))
