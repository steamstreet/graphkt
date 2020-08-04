package com.steamstreet.steamql.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeName
import graphql.language.*
import graphql.schema.idl.ScalarInfo
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File


class WiringGenerator(val schema: TypeDefinitionRegistry,
                      val packageName: String,
                      val outputDir: File) {

    val file = FileSpec.builder(packageName, "graphql-wiring")


    fun execute() {
        file.suppress("unused", "NestedLambdaShadowedImplicitParameter")
        file.addFunction(FunSpec.builder("initWiring")
                .receiver(ClassName("graphql.schema.idl", "RuntimeWiring").nestedClass("Builder"))
                .apply {
                    schema.types().values.filter {
                        it is ObjectTypeDefinition || it is InterfaceTypeDefinition
                    }.forEach { typeDef ->
                        val fields = if (typeDef is ObjectTypeDefinition) typeDef.fieldDefinitions
                        else if (typeDef is InterfaceTypeDefinition) typeDef.fieldDefinitions
                        else emptyList()

                        beginControlFlow("type(%S)", typeDef.name)
                        fields?.filter {
                            !it.inputValueDefinitions.isNullOrEmpty()
                        }?.forEach {
                            beginControlFlow("it.dataFetcher(%S)", it.name)

                            val args = ArrayList<TypeName>()
                            val parameters = it.inputValueDefinitions?.joinToString(",") {
                                val inputType = schema.findType(it.type)
                                val typeName = getTypeName(schema, it.type, packageName = packageName)


                                if (inputType is InputObjectTypeDefinition) {
                                    val nuller = if (typeName.isNullable) "" else "!!"
                                    (typeName as? ClassName)?.let { args.add(it) }
                                    """%T.fromArgument(it.getArgument("${it.name}"))${nuller}"""
                                } else if (inputType is EnumTypeDefinition) {
                                    """it.getArgument<String>("${it.name}").let { ${typeName}.valueOf(it!!) }"""
                                } else {
                                    args.add(typeName)
                                    """it.getArgument<%T>("${it.name}")"""
                                }
                            }

                            addStatement("it.getSource<${typeDef.name}>().${it.name}($parameters)", *args.toTypedArray())
                            endControlFlow()
                        }

                        if (typeDef is InterfaceTypeDefinition) {
                            beginControlFlow("it.typeResolver")
                            addStatement("val obj = it.getObject<Entity>()")
                            beginControlFlow("when (obj)")
                            getAllImplementedEntities(typeDef).forEach {
                                addStatement("is ${it.name} -> it.schema.getObjectType(\"${it.name}\")")
                            }
                            addStatement("else -> null")
                            endControlFlow()
                            endControlFlow()
                        }

                        addStatement("it")
                        endControlFlow()
                    }

                    val builtIn = ScalarInfo.STANDARD_SCALAR_DEFINITIONS.keys
                    val coercingScalar = ClassName("com.steamstreet.steamql.server.ktor", "SteamQLCoercingScalar")
                    val scalarType = ClassName("graphql.schema", "GraphQLScalarType")
                    schema.scalars().filterKeys { !builtIn.contains(it) }.values.forEach { scalar ->
                        scalar.directives.find { it.name == "SteamQLScalar" }?.let {
                            val serializerObject = (it.getArgument("serializer")?.value as? StringValue)?.value?.let {
                                ClassName(it.substringBeforeLast("."), it.substringAfterLast("."))
                            }
                            if (serializerObject != null) {
                                addStatement("scalar(%T.newScalar().name(\"${scalar.name}\").coercing(%T(%T)).build())", scalarType, coercingScalar, serializerObject)
                            }
                        }
                    }

                }
                .build())

        file.build().writeTo(outputDir)
    }

    fun getAllImplementedEntities(interfaceDef: InterfaceTypeDefinition): List<ObjectTypeDefinition> {
        val interfaceName = interfaceDef.name

        return schema.types().values.mapNotNull {
            (it as? ObjectTypeDefinition)
        }.filter { t ->
            val interfaces = t.implements?.mapNotNull {
                getTypeName(schema, it, packageName = packageName).copy(nullable = false)
            } ?: emptyList()

            interfaces.map {
                (it as ClassName).simpleName
            }.contains(interfaceName)
        }
    }
}