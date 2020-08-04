package com.steamstreet.steamql.samples.basic

import com.steamstreet.steamql.client.AppendableQueryWriter
import graphql.language.Field
import graphql.language.OperationDefinition
import graphql.parser.Parser
import org.amshove.kluent.shouldContainAll
import kotlin.test.Test

class QueryTests {
    @Test
    fun testQueryGenerator() {
        val writer = AppendableQueryWriter()

        writer.query {
            aFloat
            Another {
                anotherString
            }
        }

        // write, then parse with the official parser to confirm
        val builder = StringBuilder()
        writer.writeTo(builder)

        val parser = Parser()
        val result = parser.parseDocument(builder.toString())
        val definition = result.definitions.firstOrNull() as? OperationDefinition
        definition?.selectionSet?.selections.let {
            it?.map { it as Field }?.map { it.name }!!.shouldContainAll(listOf("aFloat", "Another"))
        }
    }
}