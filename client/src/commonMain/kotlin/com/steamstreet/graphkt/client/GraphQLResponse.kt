package com.steamstreet.graphkt.client

import com.steamstreet.graphkt.GraphQLError

interface GraphQLResponse {
    val errors: List<GraphQLError>?

    fun forElement(name: String): GraphQLResponse

    /**
     * Check if there is an error associated with the given element, and if one is found,
     * throw an exceptions
     * @throws GraphQLClientException
     */
    fun throwIfError(name: String)
}