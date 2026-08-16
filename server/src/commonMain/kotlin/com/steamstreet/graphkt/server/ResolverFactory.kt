package com.steamstreet.graphkt.server

/** Creates the root of a lightweight resolver graph once for each request. */
public fun interface ResolverFactory<in Context, out Resolver> {
    public fun create(context: Context): Resolver
}
