package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.*
import graphql.language.*
import graphql.language.TypeName
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.util.*

/**
 * Generate the query language
 */
class QueryGenerator(
    schema: TypeDefinitionRegistry,
    packageName: String,
    properties: Properties,
    outputDir: File
) : GeneratorBase(schema, packageName, properties, outputDir) {
    private val file = FileSpec.builder("$packageName.client", "query")
    private val writerClass = ClassName("com.steamstreet.graphkt.client", "QueryWriter")
    private val label = "Query"

    fun execute() {
        file.suppress("unused", "UNUSED_CHANGED_VALUE", "PropertyName", "FunctionName", "ClassName")

        schema.types().values.forEach { type ->
            if (type is ObjectTypeDefinition || type is InterfaceTypeDefinition) {
                file.addType(
                    TypeSpec.classBuilder("_${type.name}$label")
                    .addAnnotation(ClassName("com.steamstreet.graphkt", "GraphKtQuery"))
                        .primaryConstructor(FunSpec.constructorBuilder()
                                .addParameter("writer", writerClass)
                                .build())
                        .addProperty(PropertySpec.builder("writer", writerClass)
                                .initializer("writer")
                                .addModifiers(KModifier.PRIVATE)
                                .build())
                        .apply {
                            // add the typename API
                            addProperty(PropertySpec.builder("__typename", ClassName("kotlin", "Unit"))
                                    .getter(FunSpec.getterBuilder()
                                            .addStatement("""writer.println("__typename")""")
                                            .build())
                                    .build())


                            val fields = if (type is ObjectTypeDefinition) type.fieldDefinitions
                            else if (type is InterfaceTypeDefinition) type.fieldDefinitions
                            else null
                            fields?.forEach { field ->
                                val baseType = baseType(field.type)
                                val foundType = schema.types().values.find {
                                    it.name == (((baseType as? NonNullType)?.type ?: baseType) as? TypeName)?.name
                                }
                                val requiresBlock = foundType is ObjectTypeDefinition || foundType is InterfaceTypeDefinition
                                if (requiresBlock || !field.inputValueDefinitions.isNullOrEmpty()) {
                                    addFunction(FunSpec.builder(field.name).apply {
                                        field.inputValueDefinitions.forEach {
                                            addParameter(ParameterSpec.builder(it.name, getKotlinType(it.type)).build())
                                        }
                                        if (requiresBlock) {
                                            addParameter(ParameterSpec.builder("block",
                                                    LambdaTypeName.get(ClassName(clientPackage, "_${foundType?.name}$label"),
                                                            emptyList(), ClassName("kotlin", "Unit"))).build())
                                        }

                                        addStatement("""writer.print("${field.name}")""")

                                        if (!field.inputValueDefinitions.isNullOrEmpty()) {
                                            addStatement("""writer.print("(")""")

                                            if (field.inputValueDefinitions.size > 1) {
                                                addStatement("""var count = 0""")
                                            }

                                            field.inputValueDefinitions.forEach { inputDef ->
                                                val inputBaseType = (inputDef.type as? NonNullType)?.type
                                                        ?: inputDef.type

                                                if (field.inputValueDefinitions.size > 1) {
                                                    addStatement("""if (count++ > 0) writer.print(", ")""")
                                                }

                                                if (inputDef.type !is NonNullType) {
                                                    beginControlFlow("""if (${inputDef.name} != null)""")
                                                }

                                                val nonNullIndicator = if (inputDef.type is NonNullType) "!" else ""
                                                val inputType = schema.findType(inputDef.type)
                                                if (inputType is InputObjectTypeDefinition) {
                                                    if (inputBaseType is ListType) {
                                                        val elementType = inputBaseType.type
                                                        val listSerializerClass = ClassName("kotlinx.serialization.builtins", "ListSerializer")
                                                        val listNonNull = if (inputDef.type is NonNullType) "!" else ""
                                                        val listElementNonNull = if (elementType is NonNullType) "!" else ""
                                                        val elementClass = "${inputType.name}.serializer()".let {
                                                            if (elementType is NonNullType) {
                                                                it
                                                            } else {
                                                                file.addImport("kotlinx.serialization.builtins", "nullable")
                                                                "$it.nullable"
                                                            }
                                                        }
                                                        addStatement("""writer.print("${inputDef.name}: \${"$"}${"$"}{writer.variable("${inputDef.name}", "[${(baseType(elementType) as? TypeName)?.name}${listElementNonNull}]${listNonNull}", %T($elementClass), ${inputDef.name})}")""", listSerializerClass)
                                                    } else {
                                                        addStatement("""writer.print("${inputDef.name}: \${"$"}${"$"}{writer.variable("${inputDef.name}", "${(baseType(inputDef.type) as? TypeName)?.name}${nonNullIndicator}", ${inputType.name}.serializer(), ${inputDef.name})}")""")
                                                    }
                                                } else if (inputType is EnumTypeDefinition) {
                                                    addStatement(
                                                        """writer.print("${inputDef.name}: \${"$"}${"$"}{writer.variable("${inputDef.name}", "${
                                                            (baseType(
                                                                inputDef.type
                                                            ) as? TypeName)?.name
                                                        }${nonNullIndicator}", ${inputDef.name}.name)}")"""
                                                    )
                                                } else if (isCustomScalar(inputDef.type)) {
                                                    val customSerializer = scalarSerializer(inputDef.type)
                                                    if (customSerializer == null) {
                                                        file.addImport("kotlinx.serialization.builtins", "serializer")
                                                        (baseType(inputDef.type) as? TypeName)?.name?.let {
                                                            file.addImport(packageName, it)
                                                        }
                                                    }
                                                    addStatement(
                                                        """writer.print("${inputDef.name}: \${"$"}${"$"}{writer.variable("${inputDef.name}", "${
                                                            (baseType(
                                                                inputDef.type
                                                            ) as? TypeName)?.name
                                                        }${nonNullIndicator}", ${(baseType(inputDef.type) as? TypeName)?.name}.serializer(), ${inputDef.name})}")"""
                                                    )
                                                } else {
                                                    addStatement(
                                                        """writer.print("${inputDef.name}: \${"$"}${"$"}{writer.variable("${inputDef.name}", "${
                                                            (baseType(
                                                                inputDef.type
                                                            ) as? TypeName)?.name
                                                        }${nonNullIndicator}", ${inputDef.name})}")"""
                                                    )
                                                }

                                                if (inputDef.type !is NonNullType) {
                                                    nextControlFlow("else")
                                                    addStatement(
                                                        """writer.print("${inputDef.name}: \${"$"}${"$"}{writer.variable("${inputDef.name}", "${
                                                            (baseType(
                                                                inputDef.type
                                                            ) as? TypeName)?.name
                                                        }", null)}")"""
                                                    )
                                                    endControlFlow()
                                                }
                                            }

                                            addStatement("""writer.print(")")""")
                                        }

                                        if (requiresBlock) {
                                            addStatement("""writer.println(" {")""")
                                            beginControlFlow("writer.indent")
                                            addStatement("""_${foundType?.name}${label}(it).block()""")
                                            endControlFlow()
                                            addStatement("""writer.println("}")""")
                                        }

                                    }.build())
                                } else {
                                    addProperty(PropertySpec.builder(field.name, ClassName("kotlin", "Unit"))
                                            .getter(FunSpec.getterBuilder()
                                                    .addStatement("""writer.println("${field.name}")""")
                                                    .build())
                                            .build())
                                }
                            }
                        }
                        .build())
            }
        }

        schema.schemaDefinition().get().operationTypeDefinitions.forEach { operationType ->
            val jsonFunction = ClassName(packageName, "json")
            file.addFunction(FunSpec.builder(operationType.name)
                .receiver(ClassName("com.steamstreet.graphkt.client", "GraphQLClient"))
                .returns(
                    ClassName(clientPackage,
                        operationType.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() })
                )
                    .addModifiers(KModifier.SUSPEND)
                    .addParameter(ParameterSpec.builder("name", ClassName("kotlin", "String").copy(true)).defaultValue("null").build())
                    .addParameter(ParameterSpec.builder("block",
                            LambdaTypeName.get(ClassName(clientPackage, "_${operationType.typeName.name}$label"),
                                    emptyList(), ClassName("kotlin", "Unit"))).build())
                    .beginControlFlow("val result = executeAndParse(name, %T, ::${operationType.typeName.name}) {", jsonFunction)
                    .addStatement("""this.type = "${operationType.name}"""")
                    .addStatement("""_${operationType.typeName.name}Query(this).block()""")
                    .endControlFlow()
                    .addStatement("""return result""")
                    .build())
        }

        file.build().writeTo(outputDir)
    }
}