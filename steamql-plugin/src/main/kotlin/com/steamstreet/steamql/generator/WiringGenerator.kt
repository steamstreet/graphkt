package com.steamstreet.steamql.generator

import com.squareup.kotlinpoet.*
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
        file.addImport("com.fasterxml.jackson.module.kotlin", "jacksonObjectMapper")
        file.addImport("com.steamstreet.steamql.server", "graphQLJackson", "convert")
        file.addImport("com.steamstreet.steamql.server", "steamQlJson")

        file.addProperty(PropertySpec.builder("jackson",
                ClassName("com.fasterxml.jackson.databind", "ObjectMapper")).apply {
            initializer("jacksonObjectMapper()")
        }.build())

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
                            addStatement("null")
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
}