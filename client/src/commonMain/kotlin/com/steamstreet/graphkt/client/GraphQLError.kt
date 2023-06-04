package com.steamstreet.graphkt.client

import com.steamstreet.graphkt.GraphQLError

/**
 * Exception thrown from a GraphQL request. The data is dependent on the request type, but is either
 * the query or mutation.
 */
public class GraphQLClientException(public val error: GraphQLError) : Exception(error.message)