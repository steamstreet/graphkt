package com.steamstreet.graphkt.generator.schema

import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.Directive
import graphql.language.EnumTypeDefinition
import graphql.language.EnumValue
import graphql.language.FloatValue
import graphql.language.InputObjectTypeDefinition
import graphql.language.InputValueDefinition
import graphql.language.IntValue
import graphql.language.InterfaceTypeDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.NullValue
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectValue
import graphql.language.ScalarTypeDefinition
import graphql.language.StringValue
import graphql.language.Type
import graphql.language.TypeName
import graphql.language.UnionTypeDefinition
import graphql.language.Value
import graphql.schema.idl.TypeDefinitionRegistry

/** Converts the parser-specific schema registry into the GraphKt schema model. */
internal class SchemaNormalizer {
    fun normalize(registry: TypeDefinitionRegistry): SchemaModel {
        val schemaTypes = LinkedHashMap<String, SchemaType>()

        registry.types().values.forEach { definition ->
            schemaTypes[definition.name] = when (definition) {
                is ObjectTypeDefinition -> ObjectType(
                    name = definition.name,
                    description = definition.description?.content,
                    directives = definition.directives.toDirectiveUses(),
                    interfaces = definition.implements.map { it.requireNamedType() }.sorted(),
                    fields = definition.fieldDefinitions.map { field ->
                        Field(
                            name = field.name,
                            description = field.description?.content,
                            type = field.type.toTypeRef(),
                            arguments = field.inputValueDefinitions.map { it.toInputValue() }.sortedBy { it.name },
                            directives = field.directives.toDirectiveUses(),
                        )
                    }.sortedBy { it.name },
                )

                is InterfaceTypeDefinition -> InterfaceType(
                    name = definition.name,
                    description = definition.description?.content,
                    directives = definition.directives.toDirectiveUses(),
                    interfaces = definition.implements.map { it.requireNamedType() }.sorted(),
                    fields = definition.fieldDefinitions.map { field ->
                        Field(
                            name = field.name,
                            description = field.description?.content,
                            type = field.type.toTypeRef(),
                            arguments = field.inputValueDefinitions.map { it.toInputValue() }.sortedBy { it.name },
                            directives = field.directives.toDirectiveUses(),
                        )
                    }.sortedBy { it.name },
                )

                is UnionTypeDefinition -> UnionType(
                    name = definition.name,
                    description = definition.description?.content,
                    directives = definition.directives.toDirectiveUses(),
                    members = definition.memberTypes.map { it.requireNamedType() }.sorted(),
                )

                is EnumTypeDefinition -> EnumType(
                    name = definition.name,
                    description = definition.description?.content,
                    directives = definition.directives.toDirectiveUses(),
                    values = definition.enumValueDefinitions.map { value ->
                        EnumEntry(
                            name = value.name,
                            description = value.description?.content,
                            directives = value.directives.toDirectiveUses(),
                        )
                    },
                )

                is InputObjectTypeDefinition -> InputObjectType(
                    name = definition.name,
                    description = definition.description?.content,
                    directives = definition.directives.toDirectiveUses(),
                    fields = definition.inputValueDefinitions.map { it.toInputValue() }.sortedBy { it.name },
                )

                is ScalarTypeDefinition -> ScalarType(
                    name = definition.name,
                    description = definition.description?.content,
                    directives = definition.directives.toDirectiveUses(),
                )

                else -> error("Unsupported schema type: ${definition.javaClass.name}")
            }
        }

        registry.scalars().values.forEach { definition ->
            schemaTypes.putIfAbsent(
                definition.name,
                ScalarType(
                    name = definition.name,
                    description = definition.description?.content,
                    directives = definition.directives.toDirectiveUses(),
                ),
            )
        }

        specificationScalarNames.forEach { name ->
            schemaTypes.putIfAbsent(name, ScalarType(name, null, emptyList()))
        }

        return SchemaModel(
            operations = registry.operationRoots(schemaTypes.keys),
            types = schemaTypes.values.sortedBy { it.name },
            directives = registry.directiveDefinitions.values.map { definition ->
                DirectiveDefinition(
                    name = definition.name,
                    description = definition.description?.content,
                    arguments = definition.inputValueDefinitions.map { it.toInputValue() }.sortedBy { it.name },
                    repeatable = definition.isRepeatable,
                    locations = definition.directiveLocations
                        .mapTo(linkedSetOf()) { DirectiveLocation.valueOf(it.name) },
                )
            }.sortedBy { it.name },
        )
    }
}

private fun TypeDefinitionRegistry.operationRoots(typeNames: Set<String>): List<OperationRoot> {
    val explicitRoots = buildList {
        schemaDefinition().orElse(null)?.operationTypeDefinitions.orEmpty().forEach { operation ->
            add(OperationRoot(operation.name.toOperationKind(), operation.typeName.name))
        }
        schemaExtensionDefinitions.flatMap { it.operationTypeDefinitions }.forEach { operation ->
            add(OperationRoot(operation.name.toOperationKind(), operation.typeName.name))
        }
    }

    val roots = if (explicitRoots.isNotEmpty()) {
        explicitRoots
    } else {
        buildList {
            if ("Query" in typeNames) add(OperationRoot(OperationKind.QUERY, "Query"))
            if ("Mutation" in typeNames) add(OperationRoot(OperationKind.MUTATION, "Mutation"))
            if ("Subscription" in typeNames) add(OperationRoot(OperationKind.SUBSCRIPTION, "Subscription"))
        }
    }

    return roots.distinctBy { it.kind }.sortedBy { it.kind.ordinal }
}

private fun String.toOperationKind(): OperationKind = when (this) {
    "query" -> OperationKind.QUERY
    "mutation" -> OperationKind.MUTATION
    "subscription" -> OperationKind.SUBSCRIPTION
    else -> error("Unsupported operation type: $this")
}

private fun InputValueDefinition.toInputValue(): InputValue = InputValue(
    name = name,
    description = description?.content,
    type = type.toTypeRef(),
    defaultValue = defaultValue?.toConstantValue(),
    directives = directives.toDirectiveUses(),
)

private fun Type<*>.toTypeRef(): TypeRef = when (this) {
    is TypeName -> TypeRef.Named(name)
    is ListType -> TypeRef.ListType(type.toTypeRef())
    is NonNullType -> TypeRef.NonNull(type.toTypeRef())
    else -> error("Unsupported type reference: ${javaClass.name}")
}

private fun Type<*>.requireNamedType(): String =
    (this as? TypeName)?.name ?: error("Expected a named type but found ${javaClass.name}")

private fun List<Directive>.toDirectiveUses(): List<DirectiveUse> = map { directive ->
    DirectiveUse(
        name = directive.name,
        arguments = directive.arguments.map { argument ->
            DirectiveArgument(argument.name, argument.value.toConstantValue())
        }.sortedBy { it.name },
    )
}.sortedBy { it.name }

private fun Value<*>.toConstantValue(): ConstantValue = when (this) {
    is NullValue -> ConstantValue.NullValue
    is StringValue -> ConstantValue.StringValue(value ?: error("String value is missing its content"))
    is IntValue -> ConstantValue.IntValue(value.toString())
    is FloatValue -> ConstantValue.FloatValue(value.toString())
    is BooleanValue -> ConstantValue.BooleanValue(isValue)
    is EnumValue -> ConstantValue.EnumValue(name)
    is ArrayValue -> ConstantValue.ListValue(values.map { it.toConstantValue() })
    is ObjectValue -> ConstantValue.ObjectValue(
        objectFields.map { field ->
            ObjectField(field.name, field.value.toConstantValue())
        }.sortedBy { it.name },
    )
    else -> error("Unsupported constant value: ${javaClass.name}")
}
