package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asClassName
import graphql.language.EnumTypeDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.Type
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.util.*

/**
 * Base class for the various generators in the plugin.
 */
open class GraphQLGenerator(
        val schema: TypeDefinitionRegistry,
        val packageName: String,
        val properties: Properties,
        val outputDir: File
) {
    val jsonParserType = ClassName(packageName, "json")

    fun scalarClass(name: String): ClassName {
        return properties["scalar.${name}.class"]?.toString()?.let {
            ClassName.bestGuess(it)
        } ?: String::class.asClassName()
    }

    fun scalarSerializer(name: String): ClassName? {
        return properties["scalar.${name}.serializer"]?.toString()?.let {
            ClassName.bestGuess(it)
        }
    }

    fun isScalar(type: Type<Type<*>>): Boolean {
        return when (type) {
            is NonNullType -> {
                isScalar(type.type)
            }
            is graphql.language.TypeName -> {
                val name = (type as? graphql.language.TypeName)?.name
                schema.scalars().containsKey(name)
            }
            else -> {
                false
            }
        }
    }

    fun isEnum(type: Type<Type<*>>): Boolean {
        val typeDef = schema.getType(type)
        return (typeDef.isPresent && typeDef.get() is EnumTypeDefinition)
    }

    fun isCustomScalar(type: Type<Type<*>>): Boolean {
        return when (type) {
            is NonNullType -> {
                isCustomScalar(type.type)
            }
            is graphql.language.TypeName -> {
                val name = (type as? graphql.language.TypeName)?.name
                schema.customScalars().find { it.name == name } != null
            }
            else -> {
                false
            }
        }
    }

    /**
     * For a given GraphQL type, get the Kotlin type. Uses the configuration for
     * scalars.
     */
    fun getKotlinType(type: Type<Type<*>>, postfix: String = "", overriddenPackage: String? = null): TypeName {
        return when (type) {
            is ListType -> {
                val baseClass = getKotlinType(type.type, postfix, overriddenPackage)
                ClassName("kotlin.collections", "List").parameterizedBy(baseClass).copy(nullable = true)
            }
            is NonNullType -> {
                getKotlinType(type.type, postfix, overriddenPackage).copy(nullable = false)
            }
            else -> {
                val typeName = (type as? graphql.language.TypeName)
                var typePackage = overriddenPackage ?: packageName
                var simpleName = typeName?.name + postfix

                if (typeName != null && isCustomScalar(type)) {
                    return (properties["scalar.${typeName.name}.class"]?.toString()?.let {
                        ClassName(packageName, typeName.name)
                    } ?: String::class.asClassName()).copy(true)
                }

                when (typeName?.name) {
                    "ID" -> {
                        typePackage = "com.steamstreet.graphkt"
                        simpleName = "ID"
                    }
                    "String" -> {
                        typePackage = "kotlin"
                        simpleName = "String"
                    }
                    "Boolean" -> {
                        typePackage = "kotlin"
                        simpleName = "Boolean"
                    }
                    "Float" -> {
                        typePackage = "kotlin"
                        simpleName = "Float"
                    }
                    "Int" -> {
                        typePackage = "kotlin"
                        simpleName = "Int"
                    }
                    else -> {
                        // enum types DO NOT get a postfix and they always use the base package
                        val schemaType = schema.getType(typeName).get()
                        if (schemaType is EnumTypeDefinition) {
                            simpleName = typeName?.name ?: ""
                            typePackage = packageName
                        } else {
                            schema.scalars().values.find { it.name == typeName?.name }?.let {
                                simpleName = typeName?.name ?: ""
                            }
                        }
                    }
                }
                ClassName(typePackage, simpleName).copy(true)
            }
        }
    }

    val clientPackage: String get() = "${packageName}.client"
    val serverPackage: String get() = "${packageName}.server"
}