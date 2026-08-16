package com.steamstreet.graphkt.server.execution

import com.steamstreet.graphkt.OptionalInput
import kotlinx.serialization.json.JsonElement

/** Platform-neutral schema metadata used by validation and execution. */
public class GraphQLSchemaDefinition(
    public val queryType: String,
    public val mutationType: String? = null,
    public val subscriptionType: String? = null,
    types: List<GraphQLNamedType>,
    directives: List<GraphQLDirectiveDefinition> = emptyList(),
) {
    public val types: Map<String, GraphQLNamedType> = types.associateBy { it.name }
    public val directives: Map<String, GraphQLDirectiveDefinition> =
        (builtInDirectiveDefinitions + directives).associateBy { it.name }
    private val possibleObjectTypesByName: Map<String, Set<String>> by lazy {
        this.types.values.associate { type ->
            type.name to when (type) {
                is GraphQLObjectType -> setOf(type.name)
                is GraphQLUnionType -> type.members
                is GraphQLInterfaceType -> this.types.values.filterIsInstance<GraphQLObjectType>()
                    .filter { it.implementsInterface(type.name, this) }
                    .mapTo(linkedSetOf()) { it.name }

                else -> emptySet()
            }
        }
    }

    public fun type(name: String): GraphQLNamedType? = types[name]

    public fun directive(name: String): GraphQLDirectiveDefinition? = directives[name]

    internal fun rootType(operationType: OperationType): GraphQLObjectType? {
        val name = when (operationType) {
            OperationType.QUERY -> queryType
            OperationType.MUTATION -> mutationType
            OperationType.SUBSCRIPTION -> subscriptionType
        } ?: return null
        return type(name) as? GraphQLObjectType
    }

    internal fun possibleObjectTypes(typeName: String): Set<String> = possibleObjectTypesByName[typeName].orEmpty()
}

/** A directive definition used to validate executable GraphQL documents. */
public data class GraphQLDirectiveDefinition(
    public val name: String,
    public val arguments: List<GraphQLInputValueDefinition> = emptyList(),
    public val repeatable: Boolean = false,
    public val locations: Set<GraphQLDirectiveLocation>,
) {
    private val argumentsByName: Map<String, GraphQLInputValueDefinition> = arguments.associateBy { it.name }

    public fun argument(name: String): GraphQLInputValueDefinition? = argumentsByName[name]
}

/** A location where a GraphQL directive can appear. */
public enum class GraphQLDirectiveLocation {
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

public sealed interface GraphQLNamedType {
    public val name: String
}

public data class GraphQLObjectType(
    override val name: String,
    public val fields: List<GraphQLFieldDefinition>,
    public val interfaces: Set<String> = emptySet(),
) : GraphQLNamedType {
    private val fieldsByName: Map<String, GraphQLFieldDefinition> = fields.associateBy { it.name }

    public fun field(name: String): GraphQLFieldDefinition? = fieldsByName[name]
}

public data class GraphQLInterfaceType(
    override val name: String,
    public val fields: List<GraphQLFieldDefinition>,
    public val interfaces: Set<String> = emptySet(),
) : GraphQLNamedType {
    private val fieldsByName: Map<String, GraphQLFieldDefinition> = fields.associateBy { it.name }

    public fun field(name: String): GraphQLFieldDefinition? = fieldsByName[name]
}

public data class GraphQLUnionType(
    override val name: String,
    public val members: Set<String>,
) : GraphQLNamedType

public data class GraphQLEnumType(
    override val name: String,
    public val values: Set<String>,
) : GraphQLNamedType

public data class GraphQLInputObjectType(
    override val name: String,
    public val fields: List<GraphQLInputValueDefinition>,
    public val isOneOf: Boolean = false,
) : GraphQLNamedType {
    private val fieldsByName: Map<String, GraphQLInputValueDefinition> = fields.associateBy { it.name }

    public fun field(name: String): GraphQLInputValueDefinition? = fieldsByName[name]
}

public data class GraphQLScalarType(
    override val name: String,
) : GraphQLNamedType

public data class GraphQLFieldDefinition(
    public val name: String,
    public val type: GraphQLTypeRef,
    public val arguments: List<GraphQLInputValueDefinition> = emptyList(),
) {
    private val argumentsByName: Map<String, GraphQLInputValueDefinition> = arguments.associateBy { it.name }

    public fun argument(name: String): GraphQLInputValueDefinition? = argumentsByName[name]
}

public data class GraphQLInputValueDefinition(
    public val name: String,
    public val type: GraphQLTypeRef,
    public val defaultValue: OptionalInput<JsonElement> = OptionalInput.Absent,
)

public sealed interface GraphQLTypeRef {
    public data class Named(public val name: String) : GraphQLTypeRef
    public data class ListType(public val element: GraphQLTypeRef) : GraphQLTypeRef
    public data class NonNull(public val value: GraphQLTypeRef) : GraphQLTypeRef {
        init {
            require(value !is NonNull) { "A non-null type cannot wrap another non-null type" }
        }
    }
}

private val builtInDirectiveDefinitions: List<GraphQLDirectiveDefinition> = listOf(
    GraphQLDirectiveDefinition(
        name = "skip",
        arguments = listOf(
            GraphQLInputValueDefinition(
                name = "if",
                type = GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("Boolean")),
            ),
        ),
        locations = setOf(
            GraphQLDirectiveLocation.FIELD,
            GraphQLDirectiveLocation.FRAGMENT_SPREAD,
            GraphQLDirectiveLocation.INLINE_FRAGMENT,
        ),
    ),
    GraphQLDirectiveDefinition(
        name = "include",
        arguments = listOf(
            GraphQLInputValueDefinition(
                name = "if",
                type = GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("Boolean")),
            ),
        ),
        locations = setOf(
            GraphQLDirectiveLocation.FIELD,
            GraphQLDirectiveLocation.FRAGMENT_SPREAD,
            GraphQLDirectiveLocation.INLINE_FRAGMENT,
        ),
    ),
)

internal fun GraphQLTypeRef.namedType(): GraphQLTypeRef.Named = when (this) {
    is GraphQLTypeRef.Named -> this
    is GraphQLTypeRef.ListType -> element.namedType()
    is GraphQLTypeRef.NonNull -> value.namedType()
}

internal fun GraphQLTypeRef.render(): String = when (this) {
    is GraphQLTypeRef.Named -> name
    is GraphQLTypeRef.ListType -> "[${element.render()}]"
    is GraphQLTypeRef.NonNull -> "${value.render()}!"
}

internal fun GraphQLNamedType.isInputType(): Boolean =
    this is GraphQLScalarType || this is GraphQLEnumType || this is GraphQLInputObjectType

internal fun GraphQLNamedType.isLeafType(): Boolean = this is GraphQLScalarType || this is GraphQLEnumType

internal fun GraphQLNamedType.isCompositeType(): Boolean =
    this is GraphQLObjectType || this is GraphQLInterfaceType || this is GraphQLUnionType

private fun GraphQLObjectType.implementsInterface(
    interfaceName: String,
    schema: GraphQLSchemaDefinition,
    visited: MutableSet<String> = mutableSetOf(),
): Boolean {
    if (!visited.add(name)) return false
    if (interfaceName in interfaces) return true
    return interfaces.any { parentName ->
        val parent = schema.type(parentName) as? GraphQLInterfaceType ?: return@any false
        parent.implementsInterface(interfaceName, schema, visited)
    }
}

private fun GraphQLInterfaceType.implementsInterface(
    interfaceName: String,
    schema: GraphQLSchemaDefinition,
    visited: MutableSet<String>,
): Boolean {
    if (!visited.add(name)) return false
    if (interfaceName in interfaces) return true
    return interfaces.any { parentName ->
        val parent = schema.type(parentName) as? GraphQLInterfaceType ?: return@any false
        parent.implementsInterface(interfaceName, schema, visited)
    }
}
