package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.PropertySpec
import com.steamstreet.graphkt.generator.schema.ConstantValue
import com.steamstreet.graphkt.generator.schema.DirectiveDefinition
import com.steamstreet.graphkt.generator.schema.EnumType
import com.steamstreet.graphkt.generator.schema.InputObjectType
import com.steamstreet.graphkt.generator.schema.InputValue
import com.steamstreet.graphkt.generator.schema.InterfaceType
import com.steamstreet.graphkt.generator.schema.ObjectType
import com.steamstreet.graphkt.generator.schema.ScalarType
import com.steamstreet.graphkt.generator.schema.SchemaModel
import com.steamstreet.graphkt.generator.schema.SchemaType
import com.steamstreet.graphkt.generator.schema.TypeRef
import com.steamstreet.graphkt.generator.schema.UnionType
import com.steamstreet.graphkt.generator.schema.OperationKind
import java.io.File

/** Generates the platform-neutral schema metadata used by the common server core. */
internal class ServerSchemaGenerator(
    private val schema: SchemaModel,
    private val packageName: String,
    private val outputDir: File,
) {
    private val executionPackage = "com.steamstreet.graphkt.server.execution"
    private val schemaDefinitionType = ClassName(executionPackage, "GraphQLSchemaDefinition")
    private val objectType = ClassName(executionPackage, "GraphQLObjectType")
    private val interfaceType = ClassName(executionPackage, "GraphQLInterfaceType")
    private val unionType = ClassName(executionPackage, "GraphQLUnionType")
    private val enumType = ClassName(executionPackage, "GraphQLEnumType")
    private val inputObjectType = ClassName(executionPackage, "GraphQLInputObjectType")
    private val scalarType = ClassName(executionPackage, "GraphQLScalarType")
    private val fieldDefinitionType = ClassName(executionPackage, "GraphQLFieldDefinition")
    private val inputValueDefinitionType = ClassName(executionPackage, "GraphQLInputValueDefinition")
    private val directiveDefinitionType = ClassName(executionPackage, "GraphQLDirectiveDefinition")
    private val directiveLocationType = ClassName(executionPackage, "GraphQLDirectiveLocation")
    private val namedTypeRef = ClassName(executionPackage, "GraphQLTypeRef", "Named")
    private val listTypeRef = ClassName(executionPackage, "GraphQLTypeRef", "ListType")
    private val nonNullTypeRef = ClassName(executionPackage, "GraphQLTypeRef", "NonNull")
    private val optionalInputType = ClassName("com.steamstreet.graphkt", "OptionalInput")

    fun execute() {
        val file = FileSpec.builder("$packageName.server", "schema")
            .suppressingGeneratedWarnings()
            .addProperty(
                PropertySpec.builder("graphKtSchema", schemaDefinitionType)
                    .initializer(schemaDefinition())
                    .build(),
            )
            .build()
        file.writeTo(outputDir)
    }

    private fun schemaDefinition(): CodeBlock = CodeBlock.builder()
        .add("%T(\n", schemaDefinitionType)
        .indent()
        .add("queryType = %S,\n", root(OperationKind.QUERY) ?: "Query")
        .add("mutationType = %L,\n", root(OperationKind.MUTATION).nullableString())
        .add("subscriptionType = %L,\n", root(OperationKind.SUBSCRIPTION).nullableString())
        .add("types = %L,\n", list(schema.types.map(::schemaType)))
        .add("directives = %L,\n", list(schema.directives.map(::directive)))
        .unindent()
        .add(")")
        .build()

    private fun schemaType(type: SchemaType): CodeBlock = when (type) {
        is ObjectType -> CodeBlock.of(
            "%T(name = %S, fields = %L, interfaces = %L)",
            objectType,
            type.name,
            list(type.fields.map(::field)),
            set(type.interfaces),
        )

        is InterfaceType -> CodeBlock.of(
            "%T(name = %S, fields = %L, interfaces = %L)",
            interfaceType,
            type.name,
            list(type.fields.map(::field)),
            set(type.interfaces),
        )

        is UnionType -> CodeBlock.of("%T(%S, %L)", unionType, type.name, set(type.members))
        is EnumType -> CodeBlock.of("%T(%S, %L)", enumType, type.name, set(type.values.map { it.name }))
        is InputObjectType -> CodeBlock.of(
            "%T(name = %S, fields = %L, isOneOf = %L)",
            inputObjectType,
            type.name,
            list(type.fields.map(::inputValue)),
            type.directives.any { it.name == "oneOf" },
        )

        is ScalarType -> CodeBlock.of("%T(%S)", scalarType, type.name)
    }

    private fun field(field: com.steamstreet.graphkt.generator.schema.Field): CodeBlock = CodeBlock.of(
        "%T(name = %S, type = %L, arguments = %L)",
        fieldDefinitionType,
        field.name,
        typeRef(field.type),
        list(field.arguments.map(::inputValue)),
    )

    private fun inputValue(value: InputValue): CodeBlock = CodeBlock.builder()
        .add("%T(name = %S, type = %L", inputValueDefinitionType, value.name, typeRef(value.type))
        .apply {
            value.defaultValue?.let { default ->
                add(", defaultValue = %T.Present(%L)", optionalInputType, constant(default))
            }
        }
        .add(")")
        .build()

    private fun directive(directive: DirectiveDefinition): CodeBlock = CodeBlock.builder()
        .add("%T(\n", directiveDefinitionType)
        .indent()
        .add("name = %S,\n", directive.name)
        .add("arguments = %L,\n", list(directive.arguments.map(::inputValue)))
        .add("repeatable = %L,\n", directive.repeatable)
        .add(
            "locations = %L,\n",
            collection(
                "setOf",
                directive.locations.map { location -> CodeBlock.of("%T.%L", directiveLocationType, location.name) },
            ),
        )
        .unindent()
        .add(")")
        .build()

    private fun typeRef(type: TypeRef): CodeBlock = when (type) {
        is TypeRef.Named -> CodeBlock.of("%T(%S)", namedTypeRef, type.name)
        is TypeRef.ListType -> CodeBlock.of("%T(%L)", listTypeRef, typeRef(type.element))
        is TypeRef.NonNull -> CodeBlock.of("%T(%L)", nonNullTypeRef, typeRef(type.value))
    }

    private fun constant(value: ConstantValue): CodeBlock = when (value) {
        ConstantValue.NullValue -> CodeBlock.of("%T", jsonNullType)
        is ConstantValue.StringValue -> CodeBlock.of("%T(%S)", jsonPrimitiveType, value.value)
        is ConstantValue.IntValue -> CodeBlock.of("%T(%L)", jsonPrimitiveType, value.value)
        is ConstantValue.FloatValue -> CodeBlock.of("%T(%L)", jsonPrimitiveType, value.value)
        is ConstantValue.BooleanValue -> CodeBlock.of("%T(%L)", jsonPrimitiveType, value.value)
        is ConstantValue.EnumValue -> CodeBlock.of("%T(%S)", jsonPrimitiveType, value.value)
        is ConstantValue.ListValue -> CodeBlock.of("%T(%L)", jsonArrayType, list(value.values.map(::constant)))
        is ConstantValue.ObjectValue -> CodeBlock.of(
            "%T(%L)",
            jsonObjectType,
            map(value.fields.map { CodeBlock.of("%S to %L", it.name, constant(it.value)) }),
        )
    }

    private fun list(values: List<CodeBlock>): CodeBlock = collection("listOf", values)

    private fun set(values: List<String>): CodeBlock = collection(
        "setOf",
        values.map { CodeBlock.of("%S", it) },
    )

    private fun map(values: List<CodeBlock>): CodeBlock = collection("mapOf", values)

    private fun collection(factory: String, values: List<CodeBlock>): CodeBlock {
        if (values.isEmpty()) return CodeBlock.of("%L()", factory)
        return CodeBlock.builder()
            .add("%L(\n", factory)
            .indent()
            .apply { values.forEach { add("%L,\n", it) } }
            .unindent()
            .add(")")
            .build()
    }

    private fun root(kind: OperationKind): String? = schema.operations.firstOrNull { it.kind == kind }?.typeName
}

private fun String?.nullableString(): CodeBlock =
    if (this == null) CodeBlock.of("null") else CodeBlock.of("%S", this)

private fun FileSpec.Builder.suppressingGeneratedWarnings(): FileSpec.Builder = apply {
    suppress("unused", "RedundantVisibilityModifier")
}
