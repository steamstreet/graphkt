package com.steamstreet.graphkt.client

interface QueryExecutor {
    fun execute(block: QueryWriter.() -> Unit)
}

