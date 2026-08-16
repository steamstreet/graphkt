package com.steamstreet.graphkt.client.direct

import com.steamstreet.graphkt.GraphQLRequest
import com.steamstreet.graphkt.GraphQLResponseEnvelopeSerializer
import com.steamstreet.graphkt.client.AppendableQueryWriter
import com.steamstreet.graphkt.client.GraphQLClient
import com.steamstreet.graphkt.client.GraphQLSubscriptionClient
import com.steamstreet.graphkt.client.QueryWriter
import com.steamstreet.graphkt.server.GraphQLRequestScope
import com.steamstreet.graphkt.server.GraphQLServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Executes GraphQL operations against a server in the same process. */
public class GraphQLDirectClient<Context>(
    private val server: GraphQLServer<Context>,
    private val contextFactory: suspend GraphQLRequestScope.() -> Context,
) : GraphQLClient, GraphQLSubscriptionClient {
    /** Uses one fixed context value for each operation. */
    public constructor(server: GraphQLServer<Context>, context: Context) : this(server, { context })

    override suspend fun execute(
        name: String?,
        json: Json,
        block: QueryWriter.() -> Unit,
    ): String {
        val response = server.execute(createRequest(name, json, block), contextFactory)
        return json.encodeToString(GraphQLResponseEnvelopeSerializer, response)
    }

    override fun subscribe(
        name: String?,
        json: Json,
        block: QueryWriter.() -> Unit,
    ): Flow<String> {
        val request = createRequest(name, json, block)
        return server.subscribe(request, contextFactory).map { response ->
            json.encodeToString(GraphQLResponseEnvelopeSerializer, response)
        }
    }
}

private fun createRequest(
    name: String?,
    json: Json,
    block: QueryWriter.() -> Unit,
): GraphQLRequest {
    val writer = AppendableQueryWriter(json)
    if (name != null) writer.named(name)
    writer.block()
    val variables = writer.variables
        .takeIf { values -> values.isNotEmpty() }
        ?.let { values -> JsonObject(values.mapValues { (_, value) -> value.value }) }
    return GraphQLRequest(
        query = writer.toString(),
        operationName = name,
        variables = variables,
    )
}
