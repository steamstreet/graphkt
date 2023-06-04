package com.steamstreet.graphkt.client

public interface QueryExecutor {
    public fun execute(block: QueryWriter.() -> Unit)
}

