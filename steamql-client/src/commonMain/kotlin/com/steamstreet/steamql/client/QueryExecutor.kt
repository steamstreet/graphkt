package com.steamstreet.steamql.client

interface QueryExecutor {
    fun execute(block: QueryWriter.() -> Unit)
}

