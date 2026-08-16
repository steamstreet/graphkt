package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.steamstreet.graphkt.generator.schema.EnumType
import com.steamstreet.graphkt.generator.schema.Field
import com.steamstreet.graphkt.generator.schema.InputObjectType
import com.steamstreet.graphkt.generator.schema.InputValue
import com.steamstreet.graphkt.generator.schema.InterfaceType
import com.steamstreet.graphkt.generator.schema.KotlinTypeMapper
import com.steamstreet.graphkt.generator.schema.ObjectType
import com.steamstreet.graphkt.generator.schema.OperationRoot
import com.steamstreet.graphkt.generator.schema.ScalarType
import com.steamstreet.graphkt.generator.schema.SchemaModel
import com.steamstreet.graphkt.generator.schema.SchemaRelationships
import com.steamstreet.graphkt.generator.schema.SchemaType
import com.steamstreet.graphkt.generator.schema.TypeRef
import com.steamstreet.graphkt.generator.schema.UnionType
import com.steamstreet.graphkt.generator.schema.namedType
import com.steamstreet.graphkt.generator.schema.specificationScalarNames
import java.io.File
import java.util.Properties

/** Generates the type-safe client query language. */
internal class QueryGenerator(
    private val schema: SchemaModel,
    private val packageName: String,
    private val properties: Properties,
    private val outputDir: File,
) {
    private val clientPackage = "$packageName.client"
    private val file = FileSpec.builder(clientPackage, "query")
    private val writerClass = ClassName("com.steamstreet.graphkt.client", "QueryWriter")
    private val optionalInputClass = ClassName("com.steamstreet.graphkt", "OptionalInput")
    private val typeMapper = KotlinTypeMapper(schema, packageName)
    private val typesByName = schema.types.associateBy { it.name }
    private val relationships = SchemaRelationships(schema)

    fun execute() {
        file.suppress(
            "unused",
            "UNUSED_CHANGED_VALUE",
            "PropertyName",
            "FunctionName",
            "ClassName",
            "RedundantVisibilityModifier",
        )
        schema.types.filter { it is ObjectType || it is InterfaceType || it is UnionType }
            .forEach(::addSelectionType)
        schema.operations.forEach(::addOperation)

        file.build().writeTo(outputDir)
    }

    private fun addSelectionType(type: SchemaType) {
        val queryType = TypeSpec.classBuilder("_${type.name}Query")
            .addAnnotation(ClassName("com.steamstreet.graphkt", "GraphKtQuery"))
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("writer", writerClass)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("writer", writerClass)
                    .initializer("writer")
                    .addModifiers(KModifier.PRIVATE)
                    .build(),
            )

        queryType.addProperty(
            PropertySpec.builder("__typename", ClassName("kotlin", "Unit"))
                .getter(
                    FunSpec.getterBuilder()
                        .addStatement("writer.println(%S)", "__typename")
                        .build(),
                )
                .build(),
        )

        relationships.possibleTypes(type).forEach { possibleType ->
            queryType.addFunction(
                FunSpec.builder("on${possibleType.name}")
                    .addParameter(
                        "block",
                        LambdaTypeName.get(
                            receiver = ClassName(clientPackage, "_${possibleType.name}Query"),
                            returnType = ClassName("kotlin", "Unit"),
                        ),
                    )
                    .addStatement("writer.println(%S)", "... on ${possibleType.name} {")
                    .beginControlFlow("writer.indent")
                    .addStatement("%T(writer).block()", ClassName(clientPackage, "_${possibleType.name}Query"))
                    .endControlFlow()
                    .addStatement("writer.println(%S)", "}")
                    .build(),
            )
        }

        fields(type).forEach { field -> queryType.addField(field) }
        file.addType(queryType.build())
    }

    private fun TypeSpec.Builder.addField(field: Field) {
        val targetType = typesByName[field.type.namedType().name]
        val requiresBlock = targetType is ObjectType || targetType is InterfaceType || targetType is UnionType
        val selectionParameter = uniqueInternalName("_selection", field.arguments.map { it.name }.toSet())
        val argumentWritten = uniqueInternalName(
            "_graphKtArgumentWritten",
            field.arguments.map { it.name }.toSet() + selectionParameter,
        )

        if (requiresBlock || field.arguments.isNotEmpty()) {
            addFunction(
                FunSpec.builder(field.name).apply {
                    field.arguments.forEach { argument ->
                        addParameter(
                            ParameterSpec.builder(argument.name, argument.clientParameterType())
                                .apply {
                                    if (argument.usesOptionalInput()) {
                                        defaultValue("%T.Absent", optionalInputClass)
                                    }
                                }
                                .build(),
                        )
                    }
                    if (requiresBlock) {
                        addParameter(
                            ParameterSpec.builder(
                                selectionParameter,
                                LambdaTypeName.get(
                                    receiver = ClassName(clientPackage, "_${targetType.name}Query"),
                                    returnType = ClassName("kotlin", "Unit"),
                                ),
                            ).build(),
                        )
                    }

                    addStatement("writer.print(%S)", field.name)
                    if (field.arguments.isNotEmpty()) {
                        addStatement("var %N = false", argumentWritten)
                        field.arguments.forEach { argument ->
                            if (argument.usesOptionalInput()) {
                                beginControlFlow("when (val value = %N)", argument.name)
                                beginControlFlow("is %T.Present ->", optionalInputClass)
                                addArgumentPrefix(argumentWritten)
                                addVariable(argument, CodeBlock.of("value.value"))
                                endControlFlow()
                                addStatement("%T.Absent -> Unit", optionalInputClass)
                                endControlFlow()
                            } else {
                                addArgumentPrefix(argumentWritten)
                                addVariable(argument, CodeBlock.of("%N", argument.name))
                            }
                        }
                        beginControlFlow("if (%N)", argumentWritten)
                        addStatement("writer.print(%S)", ")")
                        endControlFlow()
                    }

                    if (requiresBlock) {
                        addStatement("writer.println(%S)", " {")
                        beginControlFlow("writer.indent")
                        if (targetType is InterfaceType || targetType is UnionType) {
                            addStatement("writer.println(%S)", "__typename")
                        }
                        addStatement(
                            "%T(it).%N()",
                            ClassName(clientPackage, "_${targetType.name}Query"),
                            selectionParameter,
                        )
                        endControlFlow()
                        addStatement("writer.println(%S)", "}")
                    }
                }.build(),
            )
        } else {
            addProperty(
                PropertySpec.builder(field.name, ClassName("kotlin", "Unit"))
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement("writer.println(%S)", field.name)
                            .build(),
                    )
                    .build(),
            )
        }
    }

    private fun FunSpec.Builder.addArgumentPrefix(argumentWritten: String) {
        beginControlFlow("if (%N)", argumentWritten)
        addStatement("writer.print(%S)", ", ")
        nextControlFlow("else")
        addStatement("writer.print(%S)", "(")
        addStatement("%N = true", argumentWritten)
        endControlFlow()
    }

    private fun FunSpec.Builder.addVariable(argument: InputValue, value: CodeBlock) {
        addStatement("writer.print(%S)", "${argument.name}: \$")
        addVariableValue(argument, value)
    }

    private fun FunSpec.Builder.addVariableValue(argument: InputValue, value: CodeBlock) {
        if (argument.requiresSerializer()) {
            addStatement(
                "writer.print(writer.variable(%S, %S, %L, %L))",
                argument.name,
                argument.type.renderGraphQL(),
                serializer(argument.type),
                value,
            )
        } else {
            addStatement(
                "writer.print(writer.variable(%S, %S, %L))",
                argument.name,
                argument.type.renderGraphQL(),
                value,
            )
        }
    }

    private fun InputValue.usesOptionalInput(): Boolean =
        type !is TypeRef.NonNull || defaultValue != null

    private fun InputValue.clientParameterType() = if (usesOptionalInput()) {
        optionalInputClass.parameterizedBy(typeMapper.map(type))
    } else {
        typeMapper.map(type)
    }

    private fun InputValue.requiresSerializer(): Boolean {
        if (type.containsList()) return true

        val namedType = typesByName[type.namedType().name]
        return namedType is InputObjectType ||
            namedType is EnumType ||
            namedType is ScalarType && namedType.name !in specificationScalarNames
    }

    private fun serializer(type: TypeRef): CodeBlock {
        val nullable = type !is TypeRef.NonNull
        val valueType = if (type is TypeRef.NonNull) type.value else type
        val serializer = when (valueType) {
            is TypeRef.ListType -> CodeBlock.of(
                "%M(%L)",
                MemberName("kotlinx.serialization.builtins", "ListSerializer"),
                serializer(valueType.element),
            )

            is TypeRef.Named -> configuredScalarSerializer(valueType.name)?.let {
                CodeBlock.of("%T", it)
            } ?: CodeBlock.of(
                "%M<%T>()",
                MemberName("kotlinx.serialization", "serializer"),
                typeMapper.map(TypeRef.NonNull(valueType)),
            )

            is TypeRef.NonNull -> error("Non-null type was not unwrapped")
        }

        if (!nullable) return serializer

        file.addImport("kotlinx.serialization.builtins", "nullable")
        return CodeBlock.builder().add("%L.nullable", serializer).build()
    }

    private fun configuredScalarSerializer(name: String): ClassName? {
        val type = typesByName[name]
        if (type !is ScalarType || name in specificationScalarNames) return null

        return properties["scalar.$name.serializer"]?.toString()?.let(ClassName::bestGuess)
    }

    private fun addOperation(operation: OperationRoot) {
        val operationName = operation.kind.name.lowercase()
        val rootType = typesByName[operation.typeName] as? ObjectType
            ?: error("Operation root is not an object type: ${operation.typeName}")

        file.addFunction(
            FunSpec.builder(operationName)
                .receiver(ClassName("com.steamstreet.graphkt.client", "GraphQLClient"))
                .returns(ClassName(clientPackage, rootType.name))
                .addModifiers(KModifier.SUSPEND)
                .addParameter(
                    ParameterSpec.builder("name", ClassName("kotlin", "String").copy(nullable = true))
                        .defaultValue("null")
                        .build(),
                )
                .addParameter(
                    "block",
                    LambdaTypeName.get(
                        receiver = ClassName(clientPackage, "_${rootType.name}Query"),
                        returnType = ClassName("kotlin", "Unit"),
                    ),
                )
                .beginControlFlow(
                    "val result = executeAndParse(name, %T, ::${rootType.name}Response)",
                    ClassName(packageName, "json"),
                )
                .addStatement("this.type = %S", operationName)
                .addStatement("%T(this).block()", ClassName(clientPackage, "_${rootType.name}Query"))
                .endControlFlow()
                .addStatement("return result")
                .build(),
        )
    }

    private fun fields(type: SchemaType): List<Field> = when (type) {
        is ObjectType -> type.fields
        is InterfaceType -> type.fields
        is UnionType -> emptyList()
        else -> emptyList()
    }

}

private fun uniqueInternalName(preferred: String, reserved: Set<String>): String {
    var result = preferred
    while (result in reserved) result = "_$result"
    return result
}

private fun TypeRef.renderGraphQL(): String = when (this) {
    is TypeRef.Named -> name
    is TypeRef.ListType -> "[${element.renderGraphQL()}]"
    is TypeRef.NonNull -> "${value.renderGraphQL()}!"
}

private fun TypeRef.containsList(): Boolean = when (this) {
    is TypeRef.Named -> false
    is TypeRef.ListType -> true
    is TypeRef.NonNull -> value.containsList()
}
