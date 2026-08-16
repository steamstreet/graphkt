package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.steamstreet.graphkt.generator.schema.EnumType
import com.steamstreet.graphkt.generator.schema.Field
import com.steamstreet.graphkt.generator.schema.InterfaceType
import com.steamstreet.graphkt.generator.schema.KotlinTypeMapper
import com.steamstreet.graphkt.generator.schema.ObjectType
import com.steamstreet.graphkt.generator.schema.ScalarType
import com.steamstreet.graphkt.generator.schema.SchemaModel
import com.steamstreet.graphkt.generator.schema.SchemaRelationships
import com.steamstreet.graphkt.generator.schema.SchemaType
import com.steamstreet.graphkt.generator.schema.TypeRef
import com.steamstreet.graphkt.generator.schema.UnionType
import com.steamstreet.graphkt.generator.schema.specificationScalarNames
import java.io.File
import java.util.Properties

/** Generates lazy, type-safe views over a GraphQL JSON response. */
internal class ResponseParserGenerator(
    private val schema: SchemaModel,
    private val packageName: String,
    private val properties: Properties,
    private val outputDir: File,
) {
    private val clientPackage = "$packageName.client"
    private val responseType = ClassName("com.steamstreet.graphkt.client", "GraphQLResponse")
    private val responsesFile = FileSpec.builder(clientPackage, "responses")
    private val typeMapper = KotlinTypeMapper(schema, packageName)
    private val relationships = SchemaRelationships(schema)
    private val typesByName = schema.types.associateBy { it.name }
    private val jsonParserType = ClassName(packageName, "json")

    fun execute() {
        responsesFile.suppress(
            "ComplexRedundantLet",
            "SimpleRedundantLet",
            "unused",
            "UnnecessaryVariable",
            "PropertyName",
            "RedundantVisibilityModifier",
        )

        schema.types
            .filter { it is ObjectType || it is InterfaceType || it is UnionType }
            .forEach { type ->
                addResponseInterface(type)
                addResponseClass(type)
            }

        responsesFile.build().writeTo(outputDir)
    }

    private fun addResponseInterface(type: SchemaType) {
        val inheritedFields = when (type) {
            is ObjectType -> relationships.inheritedFieldNames(type)
            is InterfaceType -> relationships.inheritedFieldNames(type)
            else -> emptySet()
        }
        val superinterfaces = superinterfaces(type)

        responsesFile.addType(
            TypeSpec.interfaceBuilder(type.name).apply {
                type.description?.let { addKdoc("%L", it) }
                superinterfaces.forEach { addSuperinterface(ClassName(clientPackage, it)) }

                addProperty(
                    PropertySpec.builder("__typename", String::class)
                        .apply {
                            if (superinterfaces.isNotEmpty()) addModifiers(KModifier.OVERRIDE)
                        }
                        .build(),
                )

                fields(type).forEach { field ->
                    addProperty(
                        PropertySpec.builder(
                            field.name,
                            typeMapper.map(field.type, overriddenPackage = clientPackage),
                        ).apply {
                            field.description?.let { addKdoc("%L", it) }
                            if (field.name in inheritedFields) addModifiers(KModifier.OVERRIDE)
                        }.build(),
                    )
                }
            }.build(),
        )
    }

    private fun addResponseClass(type: SchemaType) {
        val responseClass = TypeSpec.classBuilder("${type.name}Response")
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("_response", responseType)
                    .addParameter("_element", jsonObjectType)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("_response", responseType, KModifier.PRIVATE)
                    .initializer("_response")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("_element", jsonObjectType, KModifier.PRIVATE)
                    .initializer("_element")
                    .build(),
            )
            .addSuperinterface(ClassName(clientPackage, type.name))
            .addProperty(
                PropertySpec.builder("__typename", String::class, KModifier.OVERRIDE)
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement("return _element[%S]!!.%T.content", "__typename", jsonPrimitiveFunction)
                            .build(),
                    )
                    .build(),
            )
            .addFunction(
                FunSpec.builder("hasField")
                    .addParameter("key", String::class)
                    .returns(Boolean::class)
                    .addStatement("return _element.containsKey(key)")
                    .build(),
            )

        fields(type).forEach { field -> responseClass.addResponseField(type, field) }
        responsesFile.addType(responseClass.build())
    }

    private fun TypeSpec.Builder.addResponseField(owner: SchemaType, field: Field) {
        val fieldType = typeMapper.map(field.type, overriddenPackage = clientPackage)
        val responsePath = CodeBlock.of("_response.forElement(%S)", field.name)
        val source = CodeBlock.of("_element[%S]", field.name)

        addProperty(
            PropertySpec.builder(field.name, fieldType, KModifier.OVERRIDE)
                .getter(
                    FunSpec.getterBuilder()
                        .addStatement("_response.throwIfError(%S)", field.name)
                        .addCode("return %L\n", extract(field.type, source, responsePath, "${owner.name}: ${field.name}"))
                        .build(),
                )
                .build(),
        )
    }

    /**
     * Builds an expression that handles GraphQL nullability before decoding a present JSON value.
     * The generated expression is also used for list elements, so nested lists preserve every
     * nullable/non-null wrapper from the schema.
     */
    private fun extract(
        type: TypeRef,
        source: CodeBlock,
        responsePath: CodeBlock,
        nullMessage: String,
        depth: Int = 0,
    ): CodeBlock {
        val isNonNull = type is TypeRef.NonNull
        val valueType = if (type is TypeRef.NonNull) type.value else type
        val valueName = "value$depth"
        val extracted = extractPresent(
            type = valueType,
            source = CodeBlock.of("%L", valueName),
            responsePath = responsePath,
            nullMessage = nullMessage,
            depth = depth + 1,
        )

        return CodeBlock.builder()
            .add("(%L)?.takeIf { it !is %T }?.let { %L ->\n", source, jsonNullType, valueName)
            .indent()
            .add("%L\n", extracted)
            .unindent()
            .add("}")
            .apply {
                if (isNonNull) {
                    add(" ?: throw %T(%S)", NullPointerException::class, nullMessage)
                }
            }
            .build()
    }

    private fun extractPresent(
        type: TypeRef,
        source: CodeBlock,
        responsePath: CodeBlock,
        nullMessage: String,
        depth: Int,
    ): CodeBlock = when (type) {
        is TypeRef.NonNull -> extract(type, source, responsePath, nullMessage, depth)
        is TypeRef.ListType -> {
            val indexName = "index$depth"
            val elementName = "element$depth"
            val elementPath = CodeBlock.of("%L.forElement(%L.toString())", responsePath, indexName)
            val element = extract(
                type = type.element,
                source = CodeBlock.of("%L", elementName),
                responsePath = elementPath,
                nullMessage = nullMessage,
                depth = depth + 1,
            )

            CodeBlock.builder()
                .add("%L.%T.mapIndexed { %L, %L ->\n", source, jsonArrayFunction, indexName, elementName)
                .indent()
                .add("%L\n", element)
                .unindent()
                .add("}")
                .build()
        }

        is TypeRef.Named -> extractNamed(type.name, source, responsePath)
    }

    private fun extractNamed(
        name: String,
        source: CodeBlock,
        responsePath: CodeBlock,
    ): CodeBlock = when (name) {
        "String", "ID" -> CodeBlock.of("%L.%T.content", source, jsonPrimitiveFunction)
        "Int" -> CodeBlock.of("%L.%T.%T", source, jsonPrimitiveFunction, ClassName("kotlinx.serialization.json", "int"))
        "Float" -> CodeBlock.of("%L.%T.%T", source, jsonPrimitiveFunction, ClassName("kotlinx.serialization.json", "float"))
        "Boolean" -> CodeBlock.of(
            "%L.%T.%T",
            source,
            jsonPrimitiveFunction,
            ClassName("kotlinx.serialization.json", "boolean"),
        )

        else -> when (val definition = typesByName[name]) {
            is ScalarType -> configuredScalarSerializer(name)?.let { serializer ->
                CodeBlock.of("%T.decodeFromJsonElement(%T, %L)", jsonParserType, serializer, source)
            } ?: CodeBlock.of("%L.%T.content", source, jsonPrimitiveFunction)

            is EnumType -> CodeBlock.of(
                "%T.valueOf(%L.%T.content)",
                typeMapper.map(TypeRef.NonNull(TypeRef.Named(name))),
                source,
                jsonPrimitiveFunction,
            )

            is ObjectType -> CodeBlock.of("%T(%L, %L.%T)", responseClass(name), responsePath, source, jsonObjectFunction)
            is InterfaceType, is UnionType -> extractPolymorphic(definition, source, responsePath)
            null -> error("Schema type is not defined: $name")
            else -> error("Type cannot appear in a response: $name")
        }
    }

    private fun extractPolymorphic(
        type: SchemaType,
        source: CodeBlock,
        responsePath: CodeBlock,
    ): CodeBlock {
        val possibleTypes = relationships.possibleTypes(type)

        return CodeBlock.builder()
            .add("%L.%T.let { element ->\n", source, jsonObjectFunction)
            .indent()
            .addStatement(
                "val typeName = element[%S]?.%T?.%T",
                "__typename",
                jsonPrimitiveFunction,
                ClassName("kotlinx.serialization.json", "contentOrNull"),
            )
            .beginControlFlow("when (typeName)")
            .apply {
                possibleTypes.forEach { possibleType ->
                    addStatement(
                        "%S -> %T(%L, element)",
                        possibleType.name,
                        responseClass(possibleType.name),
                        responsePath,
                    )
                }
                addStatement("else -> %T(%L, element)", responseClass(type.name), responsePath)
            }
            .endControlFlow()
            .unindent()
            .add("}")
            .build()
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

    private fun configuredScalarSerializer(name: String): ClassName? {
        if (name in specificationScalarNames) return null
        return properties["scalar.$name.serializer"]?.toString()?.let(ClassName::bestGuess)
    }

    private fun responseClass(name: String): ClassName = ClassName(clientPackage, "${name}Response")
}
