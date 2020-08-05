package com.steamstreet.graphkt.server.ktor

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import graphql.GraphQL
import graphql.GraphQLError
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.http.ContentType
import io.ktor.request.receiveText
import io.ktor.response.respondText
import io.ktor.routing.Route
import io.ktor.routing.get
import io.ktor.routing.post
import kotlinx.serialization.Serializable

interface GraphQLConfiguration<Q, M> {
    fun initWiring(block: RuntimeWiring.Builder.() -> Unit)

    fun query(block: suspend (ApplicationCall) -> Q)
    fun mutation(block: suspend (ApplicationCall) -> M)

    /**
     * Install an error handler. This won't impact the response, but will
     * allow for extra handling (logging, etc.)
     */
    fun errorHandler(block: suspend (List<GraphQLError>) -> Unit)
}

fun <Q, M> Route.graphQL(schema: String, block: GraphQLConfiguration<Q, M>.() -> Unit) {
    graphQL(listOf(schema), block)
}

/**
 * Initialize the GraphQL system. Provide a callback that will create the root GraphQL object.
 */
@Suppress("BlockingMethodInNonBlockingContext")
fun <Q, M> Route.graphQL(schemas: List<String>, block: GraphQLConfiguration<Q, M>.() -> Unit) {
    val jackson = jacksonObjectMapper()

    var queryGetter: (suspend (ApplicationCall) -> Q)? = null
    var mutationGetter: (suspend (ApplicationCall) -> M)? = null
    var errorHandler: (suspend (List<GraphQLError>) -> Unit)? = null
    var wiringInit: (RuntimeWiring.Builder.() -> Unit)? = null
    val config: GraphQLConfiguration<Q, M> = object : GraphQLConfiguration<Q, M> {
        override fun initWiring(block: RuntimeWiring.Builder.() -> Unit) {
            wiringInit = block
        }

        override fun query(block: suspend (ApplicationCall) -> Q) {
            queryGetter = block
        }

        override fun mutation(block: suspend (ApplicationCall) -> M) {
            mutationGetter = block
        }

        override fun errorHandler(block: suspend (List<GraphQLError>) -> Unit) {
            errorHandler = block
        }
    }
    config.block()

    fun initializeGraphQL(): GraphQL {
        val wiring = RuntimeWiring.newRuntimeWiring().apply {
            wiringInit?.invoke(this)
        }.build()

        val parser = SchemaParser()
        var typeRegistry: TypeDefinitionRegistry? = null
        schemas.forEach {
            parser.parse(it)?.let {
                typeRegistry = typeRegistry?.merge(it) ?: it
            }
        }
        val graphQLSchema = SchemaGenerator().makeExecutableSchema(
                typeRegistry, wiring)
        return GraphQL.newGraphQL(graphQLSchema).build()
    }

    val graphQL = initializeGraphQL()

    @Serializable
    data class GraphQLRequestEnvelope(
            val query: String,
            val operationName: String?,
            val variables: Map<String, String>?
    )

    post {
        val request = call.receiveText()
        val envelope = jackson.readValue(request, Map::class.java)
        val context = mutationGetter?.invoke(call)
        val result = graphQL.execute {
            it.context(context)
            it.root(context)
            it.query(envelope["query"] as String)
            it.operationName(envelope["operationName"] as? String)
            envelope["variables"]?.let { variables ->
                @Suppress("UNCHECKED_CAST")
                it.variables(variables as Map<String, Any>)
            }
            it
        }
        if (!result.errors.isNullOrEmpty()) {
            errorHandler?.invoke(result.errors!!)
        }
        val response = jackson.writeValueAsString(result.toSpecification())
        call.respondText(response, ContentType.Application.Json)
    }

    get {
        val query = call.request.queryParameters["query"]
        val variablesString = call.request.queryParameters["variables"]

        val variables = if (variablesString != null) {
            jacksonObjectMapper().readValue<Map<String, Any?>>(variablesString)
        } else null

        val context = queryGetter?.invoke(call)
        val executionResult = graphQL.execute {
            it.context(context)
            it.root(context)

            if (variables != null) {
                it.variables(variables)
            }

            it.query(query)
        }
        if (!executionResult.errors.isNullOrEmpty()) {
            errorHandler?.invoke(executionResult.errors!!)
        }
        val response = jackson.writeValueAsString(executionResult.toSpecification())
        call.respondText(response, ContentType.Application.Json)
    }
}