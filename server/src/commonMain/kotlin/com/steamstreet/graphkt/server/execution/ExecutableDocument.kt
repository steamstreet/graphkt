package com.steamstreet.graphkt.server.execution

internal data class SourceLocation(
    val offset: Int,
    val line: Int,
    val column: Int,
)

internal data class ExecutableDocument(
    val operations: List<OperationDefinition>,
    val fragments: List<FragmentDefinition>,
) {
    fun selectOperation(operationName: String?): OperationDefinition {
        if (operationName != null) {
            val matches = operations.filter { it.name == operationName }
            if (matches.size != 1) {
                throw GraphQLDocumentException("Operation '$operationName' was not found")
            }
            return matches.single()
        }

        if (operations.size != 1) {
            throw GraphQLDocumentException("An operation name is required when a document contains multiple operations")
        }
        return operations.single()
    }
}

internal enum class OperationType {
    QUERY,
    MUTATION,
    SUBSCRIPTION,
}

internal data class OperationDefinition(
    val type: OperationType,
    val name: String?,
    val variables: List<VariableDefinition>,
    val directives: List<Directive>,
    val selections: List<Selection>,
    val location: SourceLocation,
)

internal data class FragmentDefinition(
    val name: String,
    val typeCondition: String,
    val directives: List<Directive>,
    val selections: List<Selection>,
    val location: SourceLocation,
)

internal data class VariableDefinition(
    val name: String,
    val type: TypeReference,
    val defaultValue: Value?,
    val directives: List<Directive>,
    val location: SourceLocation,
)

internal sealed interface TypeReference {
    data class Named(val name: String) : TypeReference
    data class ListType(val element: TypeReference) : TypeReference
    data class NonNull(val value: TypeReference) : TypeReference
}

internal sealed interface Selection {
    val location: SourceLocation
}

internal data class FieldSelection(
    val alias: String?,
    val name: String,
    val arguments: List<Argument>,
    val directives: List<Directive>,
    val selections: List<Selection>,
    override val location: SourceLocation,
) : Selection {
    val responseName: String get() = alias ?: name
}

internal data class FragmentSpread(
    val name: String,
    val directives: List<Directive>,
    override val location: SourceLocation,
) : Selection

internal data class InlineFragment(
    val typeCondition: String?,
    val directives: List<Directive>,
    val selections: List<Selection>,
    override val location: SourceLocation,
) : Selection

internal data class Directive(
    val name: String,
    val arguments: List<Argument>,
    val location: SourceLocation,
)

internal data class Argument(
    val name: String,
    val value: Value,
    val location: SourceLocation,
)

internal sealed interface Value {
    data class Variable(val name: String) : Value
    data class IntValue(val value: String) : Value
    data class FloatValue(val value: String) : Value
    data class StringValue(val value: String) : Value
    data class BooleanValue(val value: Boolean) : Value
    data object NullValue : Value
    data class EnumValue(val value: String) : Value
    data class ListValue(val values: List<Value>) : Value
    data class ObjectValue(val fields: List<ObjectField>) : Value
}

internal data class ObjectField(
    val name: String,
    val value: Value,
    val location: SourceLocation,
)

internal open class GraphQLDocumentException(
    message: String,
    val location: SourceLocation? = null,
) : IllegalArgumentException(
    if (location == null) message else "$message at ${location.line}:${location.column}",
)

internal class GraphQLDocumentLimitException(
    message: String,
    location: SourceLocation? = null,
) : GraphQLDocumentException(message, location)
