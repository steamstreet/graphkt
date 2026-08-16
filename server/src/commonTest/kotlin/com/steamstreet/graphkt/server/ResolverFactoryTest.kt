package com.steamstreet.graphkt.server

import kotlin.test.Test
import kotlin.test.assertEquals

class ResolverFactoryTest {
    @Test
    fun createsResolverFromRequestContext() {
        val factory = ResolverFactory<String, Int>(String::length)

        assertEquals(7, factory.create("request"))
    }
}
