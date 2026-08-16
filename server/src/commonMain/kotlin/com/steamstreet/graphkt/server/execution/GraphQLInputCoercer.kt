package com.steamstreet.graphkt.server.execution

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.Location
import com.steamstreet.graphkt.OptionalInput
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

internal data class InputCoercionResult(
    val values: Map<String, JsonElement>,
    val errors: List<GraphQLError>,
) {
    val isValid: Boolean get() = errors.isEmpty()
}

internal class GraphQLInputCoercer(
    private val schema: GraphQLSchemaDefinition,
) {
    fun coerceVariables(
        operation: OperationDefinition,
        variables: JsonObject?,
    ): InputCoercionResult {
        val provided = variables.orEmpty()
        val coerced = linkedMapOf<String, JsonElement>()
        val errors = mutableListOf<GraphQLError>()

        operation.variables.distinctBy { it.name }.forEach { definition ->
            val type = definition.type.toGraphQLTypeRef()
            val hasValue = definition.name in provided
            val rawValue = provided[definition.name]

            when {
                !hasValue && definition.defaultValue != null -> coerceVariable(
                    definition = definition,
                    type = type,
                    rawValue = definition.defaultValue.toJsonElement(),
                    coerced = coerced,
                    errors = errors,
                )

                !hasValue && type is GraphQLTypeRef.NonNull -> errors += variableError(
                    definition,
                    "Required variable '$${definition.name}' was not provided",
                )

                hasValue && rawValue == null -> error("Variable map contains a missing value")
                hasValue && rawValue is JsonNull && type is GraphQLTypeRef.NonNull -> errors += variableError(
                    definition,
                    "Required variable '$${definition.name}' must not be null",
                )

                hasValue -> coerceVariable(definition, type, rawValue!!, coerced, errors)
            }
        }

        return InputCoercionResult(coerced, errors)
    }

    fun coerceArguments(
        field: GraphQLFieldDefinition,
        arguments: List<Argument>,
        variables: Map<String, JsonElement>,
    ): InputCoercionResult {
        val provided = arguments.associateBy { it.name }
        val coerced = linkedMapOf<String, JsonElement>()
        val errors = mutableListOf<GraphQLError>()

        field.arguments.forEach { definition ->
            val argument = provided[definition.name]
            val resolved = when (val value = argument?.value) {
                null -> ResolvedInput.Absent
                else -> resolveLiteral(value, variables)
            }

            when {
                resolved is ResolvedInput.Absent && definition.defaultValue is OptionalInput.Present -> {
                    val defaultValue = definition.defaultValue.value
                    coerceArgument(field, definition, defaultValue, argument?.location, coerced, errors)
                }

                resolved is ResolvedInput.Absent && definition.type is GraphQLTypeRef.NonNull -> errors += argumentError(
                    field,
                    definition,
                    argument?.location,
                    "Required argument '${definition.name}' was not provided",
                )

                resolved is ResolvedInput.Present -> coerceArgument(
                    field,
                    definition,
                    resolved.value,
                    argument?.location,
                    coerced,
                    errors,
                )
            }
        }

        return InputCoercionResult(coerced, errors)
    }

    fun coerceDirectiveArguments(
        definition: GraphQLDirectiveDefinition,
        directive: Directive,
        variables: Map<String, JsonElement>,
    ): InputCoercionResult {
        val provided = directive.arguments.associateBy { it.name }
        val coerced = linkedMapOf<String, JsonElement>()
        val errors = mutableListOf<GraphQLError>()

        definition.arguments.forEach { argumentDefinition ->
            val argument = provided[argumentDefinition.name]
            val resolved = when (val value = argument?.value) {
                null -> ResolvedInput.Absent
                else -> resolveLiteral(value, variables)
            }
            val rawValue = when {
                resolved is ResolvedInput.Present -> resolved.value
                argumentDefinition.defaultValue is OptionalInput.Present -> argumentDefinition.defaultValue.value
                else -> return@forEach
            }
            try {
                coerced[argumentDefinition.name] = coerce(
                    rawValue,
                    argumentDefinition.type,
                    listOf("@${definition.name}", argumentDefinition.name),
                )
            } catch (failure: InputCoercionException) {
                errors += GraphQLError(
                    message = "Argument '@${definition.name}.${argumentDefinition.name}' is invalid: ${failure.message}",
                    locations = argument?.location?.let { location -> listOf(Location(location.line, location.column)) },
                )
            }
        }

        return InputCoercionResult(coerced, errors)
    }

    private fun coerceVariable(
        definition: VariableDefinition,
        type: GraphQLTypeRef,
        rawValue: JsonElement,
        coerced: MutableMap<String, JsonElement>,
        errors: MutableList<GraphQLError>,
    ) {
        try {
            coerced[definition.name] = coerce(rawValue, type, listOf("$${definition.name}"))
        } catch (failure: InputCoercionException) {
            errors += variableError(definition, failure.message.orEmpty())
        }
    }

    private fun coerceArgument(
        field: GraphQLFieldDefinition,
        definition: GraphQLInputValueDefinition,
        rawValue: JsonElement,
        location: SourceLocation?,
        coerced: MutableMap<String, JsonElement>,
        errors: MutableList<GraphQLError>,
    ) {
        try {
            coerced[definition.name] = coerce(rawValue, definition.type, listOf(definition.name))
        } catch (failure: InputCoercionException) {
            errors += argumentError(field, definition, location, failure.message.orEmpty())
        }
    }

    private fun coerce(
        value: JsonElement,
        type: GraphQLTypeRef,
        path: List<String>,
    ): JsonElement {
        if (type is GraphQLTypeRef.NonNull) {
            if (value is JsonNull) invalid(path, "must not be null")
            return coerce(value, type.value, path)
        }
        if (value is JsonNull) return JsonNull

        if (type is GraphQLTypeRef.ListType) {
            val values = if (value is JsonArray) value else JsonArray(listOf(value))
            return JsonArray(
                values.mapIndexed { index, element ->
                    coerce(element, type.element, path + "[$index]")
                },
            )
        }

        val namedType = type as GraphQLTypeRef.Named
        return when (val definition = schema.type(namedType.name)) {
            is GraphQLScalarType -> coerceScalar(value, definition, path)
            is GraphQLEnumType -> coerceEnum(value, definition, path)
            is GraphQLInputObjectType -> coerceInputObject(value, definition, path)
            null -> invalid(path, "uses unknown input type '${namedType.name}'")
            else -> invalid(path, "type '${namedType.name}' cannot be used as input")
        }
    }

    private fun resolveLiteral(
        value: Value,
        variables: Map<String, JsonElement>,
    ): ResolvedInput = when (value) {
        is Value.Variable -> variables[value.name]?.let(ResolvedInput::Present) ?: ResolvedInput.Absent
        is Value.ListValue -> ResolvedInput.Present(
            JsonArray(
                value.values.map { item ->
                    when (val resolved = resolveLiteral(item, variables)) {
                        ResolvedInput.Absent -> JsonNull
                        is ResolvedInput.Present -> resolved.value
                    }
                },
            ),
        )

        is Value.ObjectValue -> ResolvedInput.Present(
            JsonObject(
                buildMap {
                    value.fields.forEach { field ->
                        when (val resolved = resolveLiteral(field.value, variables)) {
                            ResolvedInput.Absent -> Unit
                            is ResolvedInput.Present -> put(field.name, resolved.value)
                        }
                    }
                },
            ),
        )

        else -> ResolvedInput.Present(value.toJsonElement())
    }

    private fun coerceScalar(
        value: JsonElement,
        type: GraphQLScalarType,
        path: List<String>,
    ): JsonElement {
        val primitive = value as? JsonPrimitive
        return when (type.name) {
            "Int" -> {
                val number = primitive?.takeUnless { it.isString }?.doubleOrNull
                if (number == null || !number.isFinite() || number % 1.0 != 0.0 ||
                    number < Int.MIN_VALUE.toDouble() || number > Int.MAX_VALUE.toDouble()
                ) {
                    invalid(path, "must be a 32-bit integer")
                }
                JsonPrimitive(number.toInt())
            }

            "Float" -> {
                val number = primitive?.takeUnless { it.isString }?.doubleOrNull
                if (number == null || !number.isFinite()) invalid(path, "must be a finite number")
                JsonPrimitive(number)
            }

            "String" -> primitive?.takeIf { it.isString }
                ?: invalid(path, "must be a string")

            "Boolean" -> primitive?.takeUnless { it.isString }?.booleanOrNull?.let(::JsonPrimitive)
                ?: invalid(path, "must be a boolean")

            "ID" -> when {
                primitive == null -> invalid(path, "must be a string or integer")
                primitive.isString -> primitive
                else -> {
                    val number = primitive.doubleOrNull
                    if (number == null || !number.isFinite() || number % 1.0 != 0.0) {
                        invalid(path, "must be a string or integer")
                    }
                    val text = if (number >= Long.MIN_VALUE.toDouble() && number <= Long.MAX_VALUE.toDouble()) {
                        number.toLong().toString()
                    } else {
                        primitive.content.substringBefore('.')
                    }
                    JsonPrimitive(text)
                }
            }

            else -> value
        }
    }

    private fun coerceEnum(
        value: JsonElement,
        type: GraphQLEnumType,
        path: List<String>,
    ): JsonElement {
        val primitive = value as? JsonPrimitive
        val enumValue = primitive?.takeIf { it.isString }?.content
        if (enumValue == null || enumValue !in type.values) {
            invalid(path, "must be one of ${type.values.sorted().joinToString()}")
        }
        return JsonPrimitive(enumValue)
    }

    private fun coerceInputObject(
        value: JsonElement,
        type: GraphQLInputObjectType,
        path: List<String>,
    ): JsonElement {
        val input = value as? JsonObject ?: invalid(path, "must be an input object of type '${type.name}'")
        val unknownFields = input.keys - type.fields.mapTo(mutableSetOf()) { it.name }
        if (unknownFields.isNotEmpty()) {
            invalid(path, "contains unknown field '${unknownFields.sorted().first()}'")
        }
        if (type.isOneOf && (input.size != 1 || input.values.singleOrNull() is JsonNull)) {
            invalid(path, "must contain exactly one non-null field for OneOf type '${type.name}'")
        }

        val result = linkedMapOf<String, JsonElement>()
        type.fields.forEach { field ->
            val hasValue = field.name in input
            val rawValue = input[field.name]
            when {
                hasValue && rawValue != null -> result[field.name] = coerce(rawValue, field.type, path + field.name)
                field.defaultValue is OptionalInput.Present -> result[field.name] = coerce(
                    field.defaultValue.value,
                    field.type,
                    path + field.name,
                )

                field.type is GraphQLTypeRef.NonNull -> invalid(path + field.name, "was not provided")
            }
        }
        if (type.isOneOf && (result.size != 1 || result.values.singleOrNull() is JsonNull)) {
            invalid(path, "must coerce to exactly one non-null field for OneOf type '${type.name}'")
        }
        return JsonObject(result)
    }

    private fun invalid(path: List<String>, message: String): Nothing {
        throw InputCoercionException("Input '${path.renderInputPath()}' $message")
    }

    private fun variableError(definition: VariableDefinition, message: String): GraphQLError = GraphQLError(
        message = message,
        locations = listOf(Location(definition.location.line, definition.location.column)),
    )

    private fun argumentError(
        field: GraphQLFieldDefinition,
        definition: GraphQLInputValueDefinition,
        location: SourceLocation?,
        message: String,
    ): GraphQLError = GraphQLError(
        message = "Argument '${field.name}.${definition.name}' is invalid: $message",
        locations = location?.let { listOf(Location(it.line, it.column)) },
    )
}

private sealed interface ResolvedInput {
    data object Absent : ResolvedInput
    data class Present(val value: JsonElement) : ResolvedInput
}

private class InputCoercionException(message: String) : IllegalArgumentException(message)

private fun TypeReference.toGraphQLTypeRef(): GraphQLTypeRef = when (this) {
    is TypeReference.Named -> GraphQLTypeRef.Named(name)
    is TypeReference.ListType -> GraphQLTypeRef.ListType(element.toGraphQLTypeRef())
    is TypeReference.NonNull -> GraphQLTypeRef.NonNull(value.toGraphQLTypeRef())
}

private fun Value.toJsonElement(): JsonElement = when (this) {
    is Value.Variable -> error("A variable cannot appear in a constant value")
    is Value.IntValue -> value.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
    is Value.FloatValue -> value.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
    is Value.StringValue -> JsonPrimitive(value)
    is Value.BooleanValue -> JsonPrimitive(value)
    Value.NullValue -> JsonNull
    is Value.EnumValue -> JsonPrimitive(value)
    is Value.ListValue -> JsonArray(values.map(Value::toJsonElement))
    is Value.ObjectValue -> JsonObject(fields.associate { it.name to it.value.toJsonElement() })
}

private fun List<String>.renderInputPath(): String = buildString {
    this@renderInputPath.forEachIndexed { index, segment ->
        if (index > 0 && !segment.startsWith("[")) append('.')
        append(segment)
    }
}
