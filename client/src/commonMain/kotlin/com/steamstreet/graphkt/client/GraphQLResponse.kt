package com.steamstreet.graphkt.client

import com.steamstreet.graphkt.GraphQLError

public interface GraphQLResponse {
    public val errors: List<GraphQLError>?

    public fun forElement(name: String): GraphQLResponse

    /**
     * Check if there is an error associated with the given element, and if one is found,
     * throw an exceptions
     * @throws GraphQLClientException
     */
    public fun throwIfError(name: String)
}