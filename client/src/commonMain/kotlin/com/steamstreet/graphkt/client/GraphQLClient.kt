package com.steamstreet.graphkt.client

/**
 * Interface for a GraphQL client.
 */
interface GraphQLClient {
    /**
     * Execute a query. Variables will be automatically included in the query based on values passed
     * when building the query. If no name is provided, a random name will be generated.
     */
    suspend fun execute(name: String? = null, block: QueryWriter.() -> Unit): String
}