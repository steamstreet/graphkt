package com.steamstreet.graphkt.generator

import graphql.language.*
import graphql.schema.idl.TypeDefinitionRegistry

fun baseType(type: Type<Type<*>>): Type<Type<*>> {
    var actualType = type
    if (actualType is NonNullType) {
        actualType = (actualType as NonNullType).type
    }
    if (actualType is ListType) {
        actualType = (actualType as ListType).type
    }
    return actualType
}


fun TypeDefinitionRegistry.findType(type: Type<Type<*>>): TypeDefinition<out TypeDefinition<*>>? {
    val baseType = baseType(type)
    return this.types().values.find {
        it.name == (baseType as TypeName).name
    }

}