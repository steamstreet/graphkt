package direct

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.client.AppendableQueryWriter
import com.steamstreet.graphkt.client.GraphQLClient
import com.steamstreet.graphkt.client.QueryWriter
import com.steamstreet.graphkt.server.RequestSelection
import com.steamstreet.graphkt.server.ServerRequestSelection
import graphql.language.OperationDefinition
import graphql.parser.Parser
import kotlinx.serialization.json.*

/**
 * The direct client calls an in-memory server implementation. This allows in-memory
 * implementations to call the server without the network overhead.
 */
class DirectClient(
    val executor: (type: String, selection: RequestSelection)-> JsonElement
) : GraphQLClient {
    private val json: Json = Json

    override suspend fun execute(
        name: String?,
        json: Json,
        block: QueryWriter.() -> Unit
    ): String {
        val writer = AppendableQueryWriter(json)
        writer.block()

        val queryString = writer.toString()
        val variables = writer.variables.let {
            if (it.isEmpty()) {
                null
            } else {
                buildJsonObject {
                    it.forEach {
                        put(it.key, it.value.value)
                    }
                }
            }
        }
        val query = parseGraphQLOperation(queryString)
        val errors = ArrayList<GraphQLError>()
        val selection = ServerRequestSelection(
            null, variables ?: emptyMap(),
            query.selectionSet ?: throw IllegalArgumentException(),
            errors
        )

        val responseElement = executor(writer.type, selection)
        return buildResponse(responseElement, emptyList()).toString()
    }

    private fun buildResponse(data: JsonElement, errors: List<GraphQLError>): JsonObject {
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

    private fun parseGraphQLOperation(query: String): OperationDefinition {
        val parser = Parser()
        val result = parser.parseDocument(query)
        return result.definitions.firstOrNull() as? OperationDefinition
            ?: throw IllegalArgumentException("Operation was not found")
    }
}


