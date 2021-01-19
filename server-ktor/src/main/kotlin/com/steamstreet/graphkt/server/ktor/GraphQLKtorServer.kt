package com.steamstreet.graphkt.server.ktor

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.server.RequestSelection
import com.steamstreet.graphkt.server.gqlContext
import graphql.language.*
import graphql.parser.Parser
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import kotlinx.serialization.json.*
import java.io.PrintWriter
import java.io.StringWriter

interface GraphQLConfiguration {
    fun query(block: suspend (ApplicationCall, RequestSelection) -> JsonElement?)
    fun mutation(block: suspend (ApplicationCall, RequestSelection) -> JsonElement?)

    /**
     * Install an error handler. This won't impact the response, but will
     * allow for extra handling (logging, etc.)
     */
    fun errorHandler(block: suspend (List<GraphQLError>) -> Unit)
}

class ServerRequestSelection(
    val parent: ServerRequestSelection?,
    val call: ApplicationCall,
    val variables: Map<String, JsonElement>,
    val node: Node<*>,
    val errors: MutableList<GraphQLError>
) : RequestSelection {
    override val name: String
        get() = (node as? NamedNode<*>)?.name ?: throw IllegalStateException("Not a named node")
    override val children: List<RequestSelection>
        get() {
            val selectionSet = when (node) {
                is Field -> node.selectionSet
                is SelectionSet -> node
                else -> TODO("not implemented")
            }
            return selectionSet.selections.map { ServerRequestSelection(this, call, variables, it, errors) }
        }
    override val parameters: Map<String, String>
        get() = TODO("not implemented")

    override fun inputParameter(key: String): JsonElement {
        val value = (node as Field).arguments.find { it.name == key }?.value
        return if (value is VariableReference) {
            val variableName = value.name
            variables[variableName]!!
        } else {
            return when (value) {
                is BooleanValue -> value.isValue.let { JsonPrimitive(it) }
                is StringValue -> value.value?.let { JsonPrimitive(it) } ?: JsonNull
                is IntValue -> value.value?.let { JsonPrimitive(it) } ?: JsonNull
                is FloatValue -> value.value?.let { JsonPrimitive(it) } ?: JsonNull
                null -> JsonNull
                else -> TODO("not implemented")
            }
        }
    }

    override fun variable(key: String): JsonElement {
        return variables[key]!!
    }

    override fun setAsContext() {
        gqlContext.set(this)
    }

    override fun error(t: Throwable) {
        val writer = StringWriter()
        val printWriter = PrintWriter(writer)
        t.printStackTrace(printWriter)
        printWriter.flush()

        val error = GraphQLError(t.message ?: "Internal Server Error", extensions = buildJsonObject {
            this.put("stacktrace", writer.toString())
        }, path = path)
        errors.add(error)
    }

    private val path: List<String>
        get() {
            return (parent?.path.orEmpty() + ((node as? NamedNode<*>)?.name)).filterNotNull()
        }
}

/**
 * Initialize the GraphQL system. Provide a callback that will create the root GraphQL object.
 */
@OptIn(KtorExperimentalAPI::class)
@Suppress("BlockingMethodInNonBlockingContext")
fun Route.graphQL(block: GraphQLConfiguration.() -> Unit) {
    val json = Json {}

    fun parseQraphQLOperation(query: String): OperationDefinition {
        val parser = Parser()
        val result = parser.parseDocument(query)
        return result.definitions.firstOrNull() as? OperationDefinition
            ?: throw IllegalArgumentException("Operation was not found")
    }

    var queryGetter: (suspend (ApplicationCall, RequestSelection) -> JsonElement?)? = null
    var mutationGetter: (suspend (ApplicationCall, RequestSelection) -> JsonElement?)? = null
    var errorHandler: (suspend (t: List<GraphQLError>) -> Unit)? = null
    val config: GraphQLConfiguration = object : GraphQLConfiguration {
        override fun query(block: suspend (ApplicationCall, RequestSelection) -> JsonElement?) {
            queryGetter = block
        }

        override fun mutation(block: suspend (ApplicationCall, RequestSelection) -> JsonElement?) {
            mutationGetter = block
        }

        override fun errorHandler(block: suspend (List<GraphQLError>) -> Unit) {
            errorHandler = block
        }
    }
    config.block()


    suspend fun ApplicationCall.respondError(t: Throwable) {
        val writer = StringWriter()
        val printWriter = PrintWriter(writer)
        t.printStackTrace(printWriter)
        printWriter.flush()

        val error = GraphQLError(t.message ?: "Internal Server Error", extensions = buildJsonObject {
            this.put("stacktrace", writer.toString())
        })

        if (errorHandler != null) {
            errorHandler?.invoke(listOf(error))
        } else {
            t.printStackTrace()
        }

        val responseEnvelope = JsonObject(mapOf("errors" to buildJsonArray {
            add(json.encodeToJsonElement(GraphQLError.serializer(), error))
        }))

        respondText(responseEnvelope.toString(), ContentType.Application.Json, HttpStatusCode.OK)
    }

    fun buildResponse(data: JsonElement, errors: List<GraphQLError>): JsonObject {
        return buildJsonObject {
            put("data", data)
            if (errors.isNotEmpty()) {
                put("errors", buildJsonArray {
                    errors.forEach {
                        add(json.encodeToJsonElement(GraphQLError.serializer(), it))
                    }
                })
            }
        }
    }

    suspend fun invoke(
        call: ApplicationCall, query: OperationDefinition?, variables: Map<String, JsonElement>?,
        function: (suspend (ApplicationCall, RequestSelection) -> JsonElement?)?
    ) {
        try {
            val errors = ArrayList<GraphQLError>()
            val selection = ServerRequestSelection(
                null, call, variables ?: emptyMap(),
                query?.selectionSet ?: throw IllegalArgumentException(),
                errors
            )
            val result = function?.invoke(call, selection) ?: throw NotFoundException()
            val response = buildResponse(result, errors)

            if (errors.isNotEmpty()) {
                errorHandler?.invoke(errors)
            }

            call.respondText(response.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        } catch (t: Throwable) {
            call.respondError(t)
        }
    }

    post {
        val request = call.receiveText()
        val requestElement = json.parseToJsonElement(request) as JsonObject
        val query = requestElement["query"]?.jsonPrimitive?.contentOrNull?.let {
            parseQraphQLOperation(it)
        }
        val variables = requestElement["variables"]?.jsonObject

        invoke(call, query, variables, mutationGetter)
    }

    get {
        val query = call.request.queryParameters["query"]?.let {
            parseQraphQLOperation(it)
        }
        val variables = call.request.queryParameters["variables"]?.let {
            json.parseToJsonElement(it) as JsonObject
        }
        invoke(call, query, variables, queryGetter)
    }
}