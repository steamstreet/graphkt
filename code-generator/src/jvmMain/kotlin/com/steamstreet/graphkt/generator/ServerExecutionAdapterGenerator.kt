package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeVariableName
import com.steamstreet.graphkt.generator.schema.OperationKind
import com.steamstreet.graphkt.generator.schema.SchemaModel
import java.io.File

/** Generates the request-scoped resolver factory adapter for the common execution engine. */
internal class ServerExecutionAdapterGenerator(
    private val schema: SchemaModel,
    private val packageName: String,
    private val outputDir: File,
) {
    private val serverPackage = "$packageName.server"
    private val resolverFactoryType = ClassName("com.steamstreet.graphkt.server", "ResolverFactory")
    private val rootFieldResolverType = ClassName("com.steamstreet.graphkt.server", "GraphQLRootFieldResolver")
    private val rootResolverFactoryType = ClassName("com.steamstreet.graphkt.server", "GraphQLRootResolverFactory")
    private val serverType = ClassName("com.steamstreet.graphkt.server", "GraphQLServer")
    private val errorMapperType = ClassName("com.steamstreet.graphkt.server", "GraphQLExecutionErrorMapper")
    private val executionPolicyType = ClassName("com.steamstreet.graphkt.server", "GraphQLExecutionPolicy")
    private val directiveHandlerType = ClassName("com.steamstreet.graphkt.server", "GraphQLDirectiveHandler")
    private val limitsType = ClassName("com.steamstreet.graphkt.server.execution", "GraphQLDocumentLimits")
    private val graphQLErrorType = ClassName("com.steamstreet.graphkt", "GraphQLError")

    fun execute() {
        val contextType = TypeVariableName("Context")
        val queryType = ClassName(serverPackage, root(OperationKind.QUERY) ?: "Query")
        val mutationType = root(OperationKind.MUTATION)?.let { ClassName(serverPackage, it) }

        val function = FunSpec.builder("graphKtServer")
            .addTypeVariable(contextType)
            .addParameter("query", resolverFactoryType.parameterizedBy(contextType, queryType))
            .apply {
                mutationType?.let { type ->
                    addParameter(
                        ParameterSpec.builder(
                            "mutation",
                            resolverFactoryType.parameterizedBy(contextType, type).copy(nullable = true),
                        ).defaultValue("null").build(),
                    )
                }
            }
            .addParameter(
                ParameterSpec.builder("limits", limitsType)
                    .defaultValue("%T()", limitsType)
                    .build(),
            )
            .addParameter(
                ParameterSpec.builder("executionPolicy", executionPolicyType)
                    .defaultValue("%T()", executionPolicyType)
                    .build(),
            )
            .addParameter(
                ParameterSpec.builder(
                    "directiveHandlers",
                    ClassName("kotlin.collections", "Map").parameterizedBy(
                        ClassName("kotlin", "String"),
                        directiveHandlerType.parameterizedBy(contextType),
                    ),
                ).defaultValue("emptyMap()").build(),
            )
            .addParameter(
                ParameterSpec.builder("errorMapper", errorMapperType.parameterizedBy(contextType))
                    .defaultValue(
                        "%T { _, _, path -> %T(message = %S, path = path) }",
                        errorMapperType,
                        graphQLErrorType,
                        "Internal Server Error",
                    )
                    .build(),
            )
            .returns(serverType.parameterizedBy(contextType))
            .addCode(serverInitializer(mutationType != null))
            .build()

        FileSpec.builder(serverPackage, "server")
            .apply { suppress("unused", "RedundantVisibilityModifier") }
            .addFunction(function)
            .build()
            .writeTo(outputDir)
    }

    private fun serverInitializer(hasMutation: Boolean): CodeBlock = CodeBlock.builder()
        .add("return %T(\n", serverType)
        .indent()
        .add("schema = graphKtSchema,\n")
        .add("query = %T { context ->\n", rootResolverFactoryType)
        .indent()
        .add("val resolver = query.create(context)\n")
        .add("%T { selection -> resolver.gqlSelectChild(selection) }\n", rootFieldResolverType)
        .unindent()
        .add("},\n")
        .apply {
            if (hasMutation) {
                add("mutation = mutation?.let { factory ->\n")
                indent()
                add("%T { context ->\n", rootResolverFactoryType)
                indent()
                add("val resolver = factory.create(context)\n")
                add("%T { selection -> resolver.gqlSelectChild(selection) }\n", rootFieldResolverType)
                unindent()
                add("}\n")
                unindent()
                add("},\n")
            }
        }
        .add("limits = limits,\n")
        .add("executionPolicy = executionPolicy,\n")
        .add("directiveHandlers = directiveHandlers,\n")
        .add("errorMapper = errorMapper,\n")
        .unindent()
        .add(")\n")
        .build()

    private fun root(kind: OperationKind): String? = schema.operations.firstOrNull { it.kind == kind }?.typeName
}
