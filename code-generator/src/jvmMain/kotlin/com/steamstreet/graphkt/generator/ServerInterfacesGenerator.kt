package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import com.steamstreet.graphkt.generator.schema.Field
import com.steamstreet.graphkt.generator.schema.InterfaceType
import com.steamstreet.graphkt.generator.schema.KotlinTypeMapper
import com.steamstreet.graphkt.generator.schema.ObjectType
import com.steamstreet.graphkt.generator.schema.OperationKind
import com.steamstreet.graphkt.generator.schema.SchemaModel
import com.steamstreet.graphkt.generator.schema.SchemaRelationships
import com.steamstreet.graphkt.generator.schema.SchemaType
import com.steamstreet.graphkt.generator.schema.UnionType
import java.io.File

/** Generates the request-scoped resolver contracts implemented by an application. */
internal class ServerInterfacesGenerator(
    private val schema: SchemaModel,
    private val packageName: String,
    private val outputDir: File,
) {
    private val serverPackage = "$packageName.server"
    private val servicesFile = FileSpec.builder(serverPackage, "services")
    private val typeMapper = KotlinTypeMapper(schema, packageName)
    private val relationships = SchemaRelationships(schema)
    private val subscriptionRootName = schema.operations
        .firstOrNull { operation -> operation.kind == OperationKind.SUBSCRIPTION }
        ?.typeName

    fun execute() {
        servicesFile.suppress("PropertyName", "RedundantVisibilityModifier")

        schema.types
            .filter { it is ObjectType || it is InterfaceType || it is UnionType }
            .forEach(::addResolverInterface)

        servicesFile.build().writeTo(outputDir)
    }

    private fun addResolverInterface(type: SchemaType) {
        val inheritedFields = when (type) {
            is ObjectType -> relationships.inheritedFieldNames(type)
            is InterfaceType -> relationships.inheritedFieldNames(type)
            else -> emptySet()
        }

        servicesFile.addType(
            TypeSpec.interfaceBuilder(type.name).apply {
                type.description?.let { addKdoc("%L", it) }
                superinterfaces(type).forEach { name ->
                    addSuperinterface(ClassName(serverPackage, name))
                }

                fields(type).forEach { field ->
                    addFunction(
                        FunSpec.builder(field.name).apply {
                            field.description?.let { addKdoc("%L", it) }
                            addModifiers(KModifier.ABSTRACT, KModifier.SUSPEND)
                            if (field.name in inheritedFields) addModifiers(KModifier.OVERRIDE)
                            val fieldType = typeMapper.map(field.type, overriddenPackage = serverPackage)
                            returns(
                                if (type.name == subscriptionRootName) {
                                    ClassName("kotlinx.coroutines.flow", "Flow").parameterizedBy(fieldType)
                                } else {
                                    fieldType
                                },
                            )
                            field.arguments.forEach { argument ->
                                addParameter(
                                    ParameterSpec.builder(argument.name, typeMapper.map(argument.type)).build(),
                                )
                            }
                        }.build(),
                    )
                }
            }.build(),
        )
    }

    private fun fields(type: SchemaType): List<Field> = when (type) {
        is ObjectType -> type.fields
        is InterfaceType -> type.fields
        is UnionType -> emptyList()
        else -> emptyList()
    }

    private fun superinterfaces(type: SchemaType): List<String> = when (type) {
        is ObjectType -> (type.interfaces + relationships.unionsOf(type).map { it.name }).distinct().sorted()
        is InterfaceType -> type.interfaces.distinct().sorted()
        else -> emptyList()
    }
}
