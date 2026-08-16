package com.steamstreet.graphkt.server

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

/** Resolves one source event against the prepared subscription selection. */
public fun interface GraphQLSubscriptionEventResolver {
    public suspend fun resolve(): JsonElement
}

/** Opens the source-event stream for one selected subscription root field. */
public fun interface GraphQLSubscriptionRootFieldResolver {
    public suspend fun subscribe(selection: RequestSelection): Flow<GraphQLSubscriptionEventResolver>
}

/** Creates one subscription root resolver after request preparation succeeds. */
public fun interface GraphQLSubscriptionRootResolverFactory<in Context> {
    public fun create(context: Context): GraphQLSubscriptionRootFieldResolver
}
