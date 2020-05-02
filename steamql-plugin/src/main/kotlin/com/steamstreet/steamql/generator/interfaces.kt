package com.steamstreet.steamql.generator

import graphql.language.FieldDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.schema.idl.TypeDefinitionRegistry

/**
 * Get the list of all fields that should be overridden by an object type
 */
fun TypeDefinitionRegistry.getOverriddenFields(typeDefinition: ObjectTypeDefinition): List<FieldDefinition> {
    return typeDefinition.implements.mapNotNull { type ->
        val interfaceName = ((type as graphql.language.TypeName).name)
        types().values.find { it.name == interfaceName }
    }.mapNotNull {
        it as? InterfaceTypeDefinition
    }.flatMap {
        it.fieldDefinitions
    }
}