package com.steamstreet.graphkt.generator.schema

/**
 * The normalized schema model used by GraphKt 3 generators.
 *
 * This model removes the GraphQL Java AST from code emitters. It also preserves the complete
 * GraphQL type wrapper structure.
 */
internal data class SchemaModel(
    val operations: List<OperationRoot>,
    val types: List<SchemaType>,
    val directives: List<DirectiveDefinition>,
) {
    inline fun <reified T : SchemaType> type(name: String): T? =
        types.filterIsInstance<T>().firstOrNull { it.name == name }
}

internal data class DirectiveDefinition(
    val name: String,
    val description: String?,
    val arguments: List<InputValue>,
    val repeatable: Boolean,
    val locations: Set<DirectiveLocation>,
)

internal enum class DirectiveLocation {
    QUERY,
    MUTATION,
    SUBSCRIPTION,
    FIELD,
    FRAGMENT_DEFINITION,
    FRAGMENT_SPREAD,
    INLINE_FRAGMENT,
    VARIABLE_DEFINITION,
    SCHEMA,
    SCALAR,
    OBJECT,
    FIELD_DEFINITION,
    ARGUMENT_DEFINITION,
    INTERFACE,
    UNION,
    ENUM,
    ENUM_VALUE,
    INPUT_OBJECT,
    INPUT_FIELD_DEFINITION,
}

internal data class OperationRoot(
    val kind: OperationKind,
    val typeName: String,
)

internal enum class OperationKind {
    QUERY,
    MUTATION,
    SUBSCRIPTION,
}

internal sealed interface SchemaType {
    val name: String
    val description: String?
    val directives: List<DirectiveUse>
}

internal data class ObjectType(
    override val name: String,
    override val description: String?,
    override val directives: List<DirectiveUse>,
    val interfaces: List<String>,
    val fields: List<Field>,
) : SchemaType

internal data class InterfaceType(
    override val name: String,
    override val description: String?,
    override val directives: List<DirectiveUse>,
    val interfaces: List<String>,
    val fields: List<Field>,
) : SchemaType

internal data class UnionType(
    override val name: String,
    override val description: String?,
    override val directives: List<DirectiveUse>,
    val members: List<String>,
) : SchemaType

internal data class EnumType(
    override val name: String,
    override val description: String?,
    override val directives: List<DirectiveUse>,
    val values: List<EnumEntry>,
) : SchemaType

internal data class EnumEntry(
    val name: String,
    val description: String?,
    val directives: List<DirectiveUse>,
)

internal data class InputObjectType(
    override val name: String,
    override val description: String?,
    override val directives: List<DirectiveUse>,
    val fields: List<InputValue>,
) : SchemaType

internal data class ScalarType(
    override val name: String,
    override val description: String?,
    override val directives: List<DirectiveUse>,
) : SchemaType

internal data class Field(
    val name: String,
    val description: String?,
    val type: TypeRef,
    val arguments: List<InputValue>,
    val directives: List<DirectiveUse>,
)

internal data class InputValue(
    val name: String,
    val description: String?,
    val type: TypeRef,
    val defaultValue: ConstantValue?,
    val directives: List<DirectiveUse>,
)

internal sealed interface TypeRef {
    data class Named(val name: String) : TypeRef
    data class ListType(val element: TypeRef) : TypeRef
    data class NonNull(val value: TypeRef) : TypeRef
}

internal data class DirectiveUse(
    val name: String,
    val arguments: List<DirectiveArgument>,
)

internal data class DirectiveArgument(
    val name: String,
    val value: ConstantValue,
)

internal sealed interface ConstantValue {
    data object NullValue : ConstantValue
    data class StringValue(val value: String) : ConstantValue
    data class IntValue(val value: String) : ConstantValue
    data class FloatValue(val value: String) : ConstantValue
    data class BooleanValue(val value: Boolean) : ConstantValue
    data class EnumValue(val value: String) : ConstantValue
    data class ListValue(val values: List<ConstantValue>) : ConstantValue
    data class ObjectValue(val fields: List<ObjectField>) : ConstantValue
}

internal data class ObjectField(
    val name: String,
    val value: ConstantValue,
)

internal val specificationScalarNames: Set<String> =
    setOf("Boolean", "Float", "ID", "Int", "String")

internal fun TypeRef.namedType(): TypeRef.Named = when (this) {
    is TypeRef.Named -> this
    is TypeRef.ListType -> element.namedType()
    is TypeRef.NonNull -> value.namedType()
}
