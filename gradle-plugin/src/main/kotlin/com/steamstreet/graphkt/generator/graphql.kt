package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
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

fun baseTypeFullyResolved(type: Type<Type<*>>): Type<Type<*>> {
    var actualType = type
    if (actualType is NonNullType) {
        actualType = baseTypeFullyResolved((actualType as NonNullType).type)
    }
    if (actualType is ListType) {
        actualType = baseTypeFullyResolved((actualType as ListType).type)
    }
    return actualType
}

fun TypeDefinitionRegistry.findType(type: Type<Type<*>>): TypeDefinition<out TypeDefinition<*>>? {
    val baseType = baseTypeFullyResolved(type)
    return this.types().values.find {
        it.name == (baseType as TypeName).name
    }
}

/**
 * Get the list of all fields that should be overridden by an object type
 */
fun TypeDefinitionRegistry.getOverriddenFields(typeDefinition: ObjectTypeDefinition): List<FieldDefinition> {
    return typeDefinition.implements.mapNotNull { type ->
        val interfaceName = ((type as TypeName).name)
        types().values.find { it.name == interfaceName }
    }.mapNotNull {
        it as? InterfaceTypeDefinition
    }.flatMap {
        it.fieldDefinitions
    }
}

fun TypeDefinitionRegistry.customScalars(): Collection<ScalarTypeDefinition> {
    return scalars().filterKeys { !builtIn.contains(it) }.values
}

fun TypeDefinitionRegistry.findScalar(type: Type<Type<*>>): ScalarTypeDefinition? {
    if (type is NonNullType) {
        return findScalar(type.type)
    }
    val typeName = (type as? TypeName)
    return customScalars().find { it.name == typeName?.name }
}

fun TypeDefinitionRegistry.findSerializer(type: Type<Type<*>>): ClassName? {
    var serializer: ClassName? = null
    findScalar(type)?.directives?.find { it.name == "GraphKTScalar" }?.let {
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