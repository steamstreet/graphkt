package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.steamstreet.graphkt.generator.schema.EnumType
import com.steamstreet.graphkt.generator.schema.Field
import com.steamstreet.graphkt.generator.schema.InputValue
import com.steamstreet.graphkt.generator.schema.InterfaceType
import com.steamstreet.graphkt.generator.schema.KotlinTypeMapper
import com.steamstreet.graphkt.generator.schema.ObjectType
import com.steamstreet.graphkt.generator.schema.OperationKind
import com.steamstreet.graphkt.generator.schema.ScalarType
import com.steamstreet.graphkt.generator.schema.SchemaModel
import com.steamstreet.graphkt.generator.schema.SchemaRelationships
import com.steamstreet.graphkt.generator.schema.SchemaType
import com.steamstreet.graphkt.generator.schema.TypeRef
import com.steamstreet.graphkt.generator.schema.UnionType
import com.steamstreet.graphkt.generator.schema.specificationScalarNames
import java.io.File
import java.util.Properties

/** Generates the common-runtime bridge between selections and resolver interfaces. */
internal class ServerMappingGenerator(
    private val schema: SchemaModel,
    private val packageName: String,
    private val properties: Properties,
    private val outputDir: File,
) {
    private val serverPackage = "$packageName.server"
    private val file = FileSpec.builder(serverPackage, "service-mapping")
    private val requestSelectionType = ClassName("com.steamstreet.graphkt.server", "RequestSelection")
    private val resolveFieldValue = MemberName("com.steamstreet.graphkt.server", "resolveFieldValue")
    private val resolveListElement = MemberName("com.steamstreet.graphkt.server", "resolveListElement")
    private val typeMapper = KotlinTypeMapper(schema, packageName)
    private val relationships = SchemaRelationships(schema)
    private val typesByName = schema.types.associateBy { it.name }
    private val jsonParserType = ClassName(packageName, "json")
    private val flowType = ClassName("kotlinx.coroutines.flow", "Flow")
    private val flowMap = MemberName("kotlinx.coroutines.flow", "map")
    private val subscriptionEventResolverType =
        ClassName("com.steamstreet.graphkt.server", "GraphQLSubscriptionEventResolver")
    private val illegalArgumentExceptionType = ClassName("kotlin", "IllegalArgumentException")
    private val subscriptionRootName = schema.operations
        .firstOrNull { operation -> operation.kind == OperationKind.SUBSCRIPTION }
        ?.typeName

    fun execute() {
        file.suppress(
            "FunctionName",
            "UNUSED_PARAMETER",
            "unused",
            "RemoveRedundantQualifierName",
            "RedundantVisibilityModifier",
        )
        schema.types
            .filter { it is ObjectType || it is InterfaceType || it is UnionType }
            .forEach { type ->
                if (type is ObjectType && type.name == subscriptionRootName) {
                    addSubscriptionMapping(type)
                } else {
                    addSelectionMapping(type)
                }
            }

        file.build().writeTo(outputDir)
    }

    private fun addSelectionMapping(type: SchemaType) {
        val resolverType = ClassName(serverPackage, type.name)

        fields(type).forEach { field -> addFieldMapping(resolverType, field) }
        addSelectChild(type, resolverType)
        addSelect(resolverType)
    }

    private fun addSubscriptionMapping(type: ObjectType) {
        val resolverType = ClassName(serverPackage, type.name)
        type.fields.forEach { field -> addSubscriptionFieldMapping(resolverType, field) }
        addSubscribeChild(type, resolverType)
    }

    private fun addSubscriptionFieldMapping(receiver: ClassName, field: Field) {
        val resolverCall = CodeBlock.builder()
            .add("%N(", field.name)
            .apply {
                field.arguments.forEachIndexed { index, argument ->
                    if (index > 0) add(", ")
                    add("%N", argument.name)
                }
            }
            .add(")")
            .build()

        file.addFunction(
            FunSpec.builder("gql_subscribe_${field.name}")
                .receiver(receiver)
                .addModifiers(KModifier.SUSPEND)
                .returns(flowType.parameterizedBy(subscriptionEventResolverType))
                .addParameter("field", requestSelectionType)
                .apply {
                    field.arguments.forEach { argument ->
                        addParameter(ParameterSpec.builder(argument.name, typeMapper.map(argument.type)).build())
                    }
                }
                .addCode(
                    CodeBlock.builder()
                        .add("return %L.%M { value ->\n", resolverCall, flowMap)
                        .indent()
                        .add("%T {\n", subscriptionEventResolverType)
                        .indent()
                        .add(
                            "field.%M(nonNull = %L) {\n",
                            resolveFieldValue,
                            field.type is TypeRef.NonNull,
                        )
                        .indent()
                        .add("%L\n", encode(field.type, CodeBlock.of("value"), CodeBlock.of("field")))
                        .unindent()
                        .add("}\n")
                        .unindent()
                        .add("}\n")
                        .unindent()
                        .add("}\n")
                        .build(),
                )
                .build(),
        )
    }

    private fun addSubscribeChild(type: ObjectType, receiver: ClassName) {
        file.addFunction(
            FunSpec.builder("gqlSubscribe")
                .receiver(receiver)
                .addParameter("child", requestSelectionType)
                .addModifiers(KModifier.SUSPEND)
                .returns(flowType.parameterizedBy(subscriptionEventResolverType))
                .beginControlFlow("return when (child.name)")
                .apply {
                    type.fields.forEach { field ->
                        addStatement(
                            "%S -> %N(child%L)",
                            field.name,
                            "gql_subscribe_${field.name}",
                            decodedArguments(field.arguments),
                        )
                    }
                    addStatement(
                        "else -> throw %T(%P)",
                        illegalArgumentExceptionType,
                        "Unknown subscription field '${'$'}{child.name}' on ${type.name}",
                    )
                }
                .endControlFlow()
                .build(),
        )
    }

    private fun addFieldMapping(receiver: ClassName, field: Field) {
        val resolverCall = CodeBlock.builder()
            .add("%N(", field.name)
            .apply {
                field.arguments.forEachIndexed { index, argument ->
                    if (index > 0) add(", ")
                    add("%N", argument.name)
                }
            }
            .add(")")
            .build()

        file.addFunction(
            FunSpec.builder("gql_${field.name}")
                .receiver(receiver)
                .addModifiers(KModifier.SUSPEND)
                .returns(jsonElementType)
                .addParameter("field", requestSelectionType)
                .apply {
                    field.arguments.forEach { argument ->
                        addParameter(ParameterSpec.builder(argument.name, typeMapper.map(argument.type)).build())
                    }
                }
                .addCode("return %L\n", encode(field.type, resolverCall, CodeBlock.of("field")))
                .build(),
        )
    }

    private fun addSelectChild(type: SchemaType, receiver: ClassName) {
        file.addFunction(
            FunSpec.builder("gqlSelectChild")
                .receiver(receiver)
                .addParameter("child", requestSelectionType)
                .addModifiers(KModifier.SUSPEND)
                .returns(jsonElementType.copy(nullable = true))
                .addStatement("val runtimeType = %L", runtimeTypeName(type))
                .addStatement("if (!child.appliesTo(runtimeType)) return null")
                .beginControlFlow("return when (child.name)")
                .apply {
                    fields(type).forEach { field ->
                        beginControlFlow(
                            "%S -> child.%M(nonNull = %L)",
                            field.name,
                            resolveFieldValue,
                            field.type is TypeRef.NonNull,
                        )
                        addStatement("%N(child%L)", "gql_${field.name}", decodedArguments(field.arguments))
                        endControlFlow()
                    }
                    beginControlFlow("%S -> child.%M(nonNull = true)", "__typename", resolveFieldValue)
                    addStatement("%T(runtimeType)", jsonPrimitiveType)
                    endControlFlow()

                    if (type is InterfaceType || type is UnionType) {
                        addCode("else -> %L\n", dispatchAbstractSelection(type))
                    } else {
                        addStatement(
                            "else -> throw %T(%P)",
                            illegalArgumentExceptionType,
                            "Unknown field '${'$'}{child.name}' on ${type.name}",
                        )
                    }
                }
                .endControlFlow()
                .build(),
        )
    }

    private fun addSelect(receiver: ClassName) {
        file.addFunction(
            FunSpec.builder("gqlSelect")
                .receiver(receiver)
                .addParameter("field", requestSelectionType)
                .addModifiers(KModifier.SUSPEND)
                .returns(jsonElementType)
                .beginControlFlow("val fields = field.children.mapNotNull { child ->")
                .addStatement("val value = gqlSelectChild(child)")
                .beginControlFlow("if (value != null)")
                .addStatement("child.responseName to value")
                .nextControlFlow("else")
                .addStatement("null")
                .endControlFlow()
                .endControlFlow()
                .addStatement("return %T(fields.toMap())", jsonObjectType)
                .build(),
        )
    }

    private fun decodedArguments(arguments: List<InputValue>): CodeBlock = CodeBlock.builder().apply {
        arguments.forEach { argument ->
            add(", %T.decodeFromJsonElement(%L, child.inputParameter(%S))", jsonParserType, serializer(argument.type), argument.name)
        }
    }.build()

    private fun serializer(type: TypeRef): CodeBlock {
        val isNullable = type !is TypeRef.NonNull
        val valueType = if (type is TypeRef.NonNull) type.value else type
        val serializer = when (valueType) {
            is TypeRef.ListType -> CodeBlock.of(
                "%M(%L)",
                MemberName("kotlinx.serialization.builtins", "ListSerializer"),
                serializer(valueType.element),
            )

            is TypeRef.Named -> configuredScalarSerializer(valueType.name)?.let { configured ->
                CodeBlock.of("%T", configured)
            } ?: CodeBlock.of(
                "%M<%T>()",
                MemberName("kotlinx.serialization", "serializer"),
                typeMapper.map(TypeRef.NonNull(valueType)),
            )

            is TypeRef.NonNull -> error("Non-null type was not unwrapped")
        }

        if (!isNullable) return serializer

        file.addImport("kotlinx.serialization.builtins", "nullable")
        return CodeBlock.of("%L.nullable", serializer)
    }

    private fun encode(
        type: TypeRef,
        value: CodeBlock,
        selection: CodeBlock,
        depth: Int = 0,
    ): CodeBlock {
        if (type is TypeRef.NonNull) {
            return encodePresent(type.value, value, selection, depth)
        }

        val valueName = "value$depth"
        return CodeBlock.builder()
            .add("%L?.let { %L ->\n", value, valueName)
            .indent()
            .add("%L\n", encodePresent(type, CodeBlock.of("%L", valueName), selection, depth + 1))
            .unindent()
            .add("} ?: %T", jsonNullType)
            .build()
    }

    private fun encodePresent(
        type: TypeRef,
        value: CodeBlock,
        selection: CodeBlock,
        depth: Int,
    ): CodeBlock = when (type) {
        is TypeRef.NonNull -> encode(type, value, selection, depth)
        is TypeRef.ListType -> {
            val elementName = "element$depth"
            val indexName = "index$depth"
            val elementSelectionName = "elementSelection$depth"
            CodeBlock.builder()
                .add("%T(%L.mapIndexed { %L, %L ->\n", jsonArrayType, value, indexName, elementName)
                .indent()
                .add("%L.%M(%L, nonNull = %L) { %L ->\n", selection, resolveListElement, indexName, type.element is TypeRef.NonNull, elementSelectionName)
                .indent()
                .add("%L\n", encode(type.element, CodeBlock.of("%L", elementName), CodeBlock.of("%L", elementSelectionName), depth + 1))
                .unindent()
                .add("}\n")
                .unindent()
                .add("})")
                .build()
        }

        is TypeRef.Named -> encodeNamed(type.name, value, selection)
    }

    private fun encodeNamed(
        name: String,
        value: CodeBlock,
        selection: CodeBlock,
    ): CodeBlock = when (val definition = typesByName[name]) {
        is EnumType -> CodeBlock.of("%T(%L.name)", jsonPrimitiveType, value)
        is ScalarType -> configuredScalarSerializer(name)?.let { serializer ->
            CodeBlock.of("%T.encodeToJsonElement(%T, %L)", jsonParserType, serializer, value)
        } ?: CodeBlock.of("%T(%L)", jsonPrimitiveType, value)

        is ObjectType, is InterfaceType, is UnionType -> CodeBlock.of("%L.gqlSelect(%L)", value, selection)
        null -> error("Schema type is not defined: $name")
        else -> error("Type cannot appear in a response: $name")
    }

    private fun runtimeTypeName(type: SchemaType): CodeBlock = when (type) {
        is ObjectType -> CodeBlock.of("%S", type.name)
        is InterfaceType, is UnionType -> CodeBlock.builder()
            .beginControlFlow("when (this)")
            .apply {
                relationships.possibleTypes(type).forEach { possibleType ->
                    add("is %T -> %S\n", ClassName(serverPackage, possibleType.name), possibleType.name)
                }
                add(
                    "else -> throw %T(%S)\n",
                    illegalArgumentExceptionType,
                    "Resolver does not implement a concrete type for ${type.name}",
                )
            }
            .endControlFlow()
            .build()

        else -> error("Type does not support selections: ${type.name}")
    }

    private fun dispatchAbstractSelection(type: SchemaType): CodeBlock = CodeBlock.builder()
        .beginControlFlow("when (child.typeName)")
        .apply {
            dispatchTypes(type).forEach { dispatchType ->
                val resolverType = ClassName(serverPackage, dispatchType.name)
                add(
                    "%S -> (this as? %T)?.gqlSelectChild(child)\n",
                    dispatchType.name,
                    resolverType,
                )
            }
            add(
                "else -> throw %T(%P)\n",
                illegalArgumentExceptionType,
                "Unknown selection '${'$'}{child.name}' for ${type.name}",
            )
        }
        .endControlFlow()
        .build()

    private fun dispatchTypes(type: SchemaType): List<SchemaType> {
        val possibleTypeNames = possibleObjectTypeNames(type)
        return schema.types
            .filter { candidate ->
                candidate.name != type.name &&
                    (candidate is ObjectType || candidate is InterfaceType) &&
                    possibleObjectTypeNames(candidate).any { it in possibleTypeNames }
            }
            .sortedBy { it.name }
    }

    private fun possibleObjectTypeNames(type: SchemaType): Set<String> = when (type) {
        is ObjectType -> setOf(type.name)
        is InterfaceType, is UnionType -> relationships.possibleTypes(type).mapTo(linkedSetOf()) { it.name }
        else -> emptySet()
    }

    private fun fields(type: SchemaType): List<Field> = when (type) {
        is ObjectType -> type.fields
        is InterfaceType -> type.fields
        is UnionType -> emptyList()
        else -> emptyList()
    }

    private fun configuredScalarSerializer(name: String): ClassName? {
        val definition = typesByName[name]
        if (definition !is ScalarType || name in specificationScalarNames) return null
        return properties["scalar.$name.serializer"]?.toString()?.let(ClassName::bestGuess)
    }
}
