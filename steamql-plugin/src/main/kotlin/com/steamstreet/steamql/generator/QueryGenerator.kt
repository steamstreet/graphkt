package com.steamstreet.steamql.generator

import com.squareup.kotlinpoet.*
import graphql.language.*
import graphql.language.TypeName
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File

/**
 * Generate the query language
 */
class QueryGenerator(private val schema: TypeDefinitionRegistry,
                     private val packageName: String,
                     private val outputDir: File) {
    private val file = FileSpec.builder(packageName, "graphql-query")
    private val writerClass = ClassName("com.steamstreet.steamql.client", "QueryWriter")
    private val label = "Query"

    fun execute() {
        file.suppress("unused", "UNUSED_CHANGED_VALUE")

        schema.types().values.forEach { type ->
            if (type is ObjectTypeDefinition || type is InterfaceTypeDefinition) {
                file.addType(TypeSpec.classBuilder("${type.name}$label")
                        .primaryConstructor(FunSpec.constructorBuilder()
                                .addParameter("writer", writerClass)
                                .build())
                        .addProperty(PropertySpec.builder("writer", writerClass)
                                .initializer("writer")
                                .addModifiers(KModifier.PRIVATE)
                                .build())
                        .apply {
                            val fields = if (type is ObjectTypeDefinition) type.fieldDefinitions
                            else if (type is InterfaceTypeDefinition) type.fieldDefinitions
                            else null
                            fields?.forEach { field ->
                                val baseType = baseType(field.type)
                                val foundType = schema.types().values.find {
                                    it.name == (baseType as TypeName).name
                                }
                                val requiresBlock = foundType is ObjectTypeDefinition || foundType is InterfaceTypeDefinition
                                if (requiresBlock || !field.inputValueDefinitions.isNullOrEmpty()) {
                                    addFunction(FunSpec.builder(field.name).apply {
                                        field.inputValueDefinitions.forEach {
                                            addParameter(ParameterSpec.builder(it.name, getTypeName(schema, it.type, packageName = packageName)).build())
                                        }
                                            if (requiresBlock) {
                                                addParameter(ParameterSpec.builder("block",
                                                        LambdaTypeName.get(ClassName(packageName, "${foundType?.name}$label"),
                                                                emptyList(), ClassName("kotlin", "Unit"))).build())
                                            }

                                            addStatement("""writer.print("${field.name}")""")

                                            if (!field.inputValueDefinitions.isNullOrEmpty()) {
                                                addStatement("""writer.print("(")""")

                                                if (field.inputValueDefinitions.size > 1) {
                                                    addStatement("""var count = 0""")
                                                }

                                                field.inputValueDefinitions.forEach { inputDef ->
                                                    val inputType = schema.findType(inputDef.type)

                                                    if (inputDef.type !is NonNullType) {
                                                        beginControlFlow("""if (${inputDef.name} != null)""")
                                                    }

                                                    if (field.inputValueDefinitions.size > 1) {
                                                        addStatement("""if (count++ > 0) writer.print(", ")""")
                                                    }

                                                    if (inputType is InputObjectTypeDefinition) {
                                                        addStatement("""writer.print("${inputDef.name}: \${"$"}${"$"}{writer.variable("${inputDef.name}", "${(baseType(inputDef.type) as? TypeName)?.name}", ${inputType.name}.serializer(), ${inputDef.name})}")""")
                                                    } else if (inputType is EnumTypeDefinition) {
                                                        addStatement("""writer.print("${inputDef.name}: \${"$"}${"$"}{writer.variable("${inputDef.name}", "${(baseType(inputDef.type) as? TypeName)?.name}", ${inputDef.name}.name)}")""")
                                                    } else {
                                                        addStatement("""writer.print("${inputDef.name}: \${"$"}${"$"}{writer.variable("${inputDef.name}", "${(baseType(inputDef.type) as? TypeName)?.name}", ${inputDef.name})}")""")
                                                    }

                                                    if (inputDef.type !is NonNullType) {
                                                        endControlFlow()
                                                    }
                                                }

                                                addStatement("""writer.print(")")""")
                                            }

                                            if (requiresBlock) {
                                                addStatement("""writer.println(" {")""")
                                                beginControlFlow("writer.indent")
                                                addStatement("""${foundType?.name}${label}(it).block()""")
                                                endControlFlow()
                                                addStatement("""writer.println("}")""")
                                            }

                                        }.build())
                                    } else {
                                        val typeName = getTypeName(schema, baseType, packageName = packageName)
                                        addProperty(PropertySpec.builder(field.name, typeName)
                                                .getter(FunSpec.getterBuilder()
                                                        .addStatement("""writer.println("${field.name}")""")
                                                        .addStatement("""return null""").build())
                                                .build())
                                    }
                                }
                            }
                            .build())
            }
        }

        schema.schemaDefinition().get().operationTypeDefinitions.forEach { operationType ->
            file.addFunction(FunSpec.builder(operationType.name)
                    .receiver(ClassName("com.steamstreet.steamql.client", "QueryWriter"))
                    .addParameter(ParameterSpec.builder("block",
                            LambdaTypeName.get(ClassName(packageName, "${operationType.typeName.name}$label"),
                                    emptyList(), ClassName("kotlin", "Unit"))).build())
                    .addStatement("""this.type = "${operationType.name}"""")
                    .addStatement("""${operationType.typeName.name}Query(this).block()""")
                    .build())
        }

        file.build().writeTo(outputDir)
    }
}