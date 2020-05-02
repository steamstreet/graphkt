package com.steamstreet.steamql.generator

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import graphql.language.NonNullType
import graphql.language.ScalarTypeDefinition
import graphql.language.StringValue
import graphql.language.Type
import graphql.schema.idl.TypeDefinitionRegistry

fun TypeDefinitionRegistry.customScalars(): Collection<ScalarTypeDefinition> {
    return scalars().filterKeys { !builtIn.contains(it) }.values
}

fun TypeDefinitionRegistry.findScalar(type: Type<Type<*>>): ScalarTypeDefinition? {
    if (type is NonNullType) {
        return findScalar(type.type)
    }
    val typeName = (type as? graphql.language.TypeName)
    return customScalars().find { it.name == typeName?.name }
}

fun TypeDefinitionRegistry.findSerializer(type: Type<Type<*>>): ClassName? {
    var serializer: ClassName? = null
    findScalar(type)?.directives?.find { it.name == "SteamQLScalar" }?.let {
        (it.getArgument("serializer")?.value as? StringValue)?.value?.let {
            serializer = ClassName(it.substringBeforeLast("."), it.substringAfterLast("."))
        }
    }
    return serializer
}

fun TypeDefinitionRegistry.buildSerializableAnnotation(type: Type<Type<*>>): AnnotationSpec? {
    return findSerializer(type)?.let { serializer ->
        AnnotationSpec.builder(
                ClassName("kotlinx.serialization", "Serializable")
        ).addMember("""with=%T::class""", serializer).build()
    }
}