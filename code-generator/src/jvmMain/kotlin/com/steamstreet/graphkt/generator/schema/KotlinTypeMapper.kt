package com.steamstreet.graphkt.generator.schema

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName

/** Maps normalized GraphQL type references to generated Kotlin types. */
internal class KotlinTypeMapper(
    private val schema: SchemaModel,
    private val packageName: String,
) {
    private val typesByName = schema.types.associateBy { it.name }

    fun map(
        type: TypeRef,
        postfix: String = "",
        overriddenPackage: String? = null,
    ): TypeName = when (type) {
        is TypeRef.ListType -> ClassName("kotlin.collections", "List")
            .parameterizedBy(map(type.element, postfix, overriddenPackage))
            .copy(nullable = true)

        is TypeRef.NonNull -> map(type.value, postfix, overriddenPackage).copy(nullable = false)
        is TypeRef.Named -> mapNamed(type.name, postfix, overriddenPackage)
    }

    private fun mapNamed(
        name: String,
        postfix: String,
        overriddenPackage: String?,
    ): TypeName {
        val builtInType = when (name) {
            "ID" -> ClassName("com.steamstreet.graphkt", "ID")
            "String" -> ClassName("kotlin", "String")
            "Boolean" -> ClassName("kotlin", "Boolean")
            "Float" -> ClassName("kotlin", "Float")
            "Int" -> ClassName("kotlin", "Int")
            else -> null
        }
        if (builtInType != null) {
            return builtInType.copy(nullable = true)
        }

        val definition = typesByName[name]
            ?: error("Schema type is not defined: $name")

        val generatedPackage = when (definition) {
            is EnumType, is ScalarType -> packageName
            else -> overriddenPackage ?: packageName
        }
        val generatedName = when (definition) {
            is EnumType, is ScalarType -> name
            else -> name + postfix
        }

        return ClassName(generatedPackage, generatedName).copy(nullable = true)
    }
}
