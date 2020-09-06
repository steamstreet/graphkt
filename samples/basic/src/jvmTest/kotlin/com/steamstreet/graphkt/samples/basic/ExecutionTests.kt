package com.steamstreet.graphkt.samples.basic

import graphql.language.Field
import graphql.language.OperationDefinition
import graphql.parser.Parser
import kotlinx.serialization.ImplicitReflectionSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.stringify
import kotlin.test.Test

class ExecutionTests {
    @ImplicitReflectionSerializer
    @Test
    fun testBasics() {
        val query = """
query somequery(${'$'}id: String) {
    name
    another {
        age
    }
    allOthers {
        age
    }
    getSomething(id: ${'$'}id) {
        age
    }
}
        """.trimIndent()

        val parser = Parser()
        val result = parser.parseDocument(query)
        val definition = result.definitions.firstOrNull() as? OperationDefinition

        // get the base context
        val base = QueryImpl()

        val rootSelections = definition?.selectionSet?.selections?.mapNotNull { it as? Field }?.map { FieldRequestSelection(it) }
        rootSelections?.let {
            val jsonResult = base.gqlSelect(it)
            println(Json(JsonConfiguration.Stable).stringify(jsonResult))
        }
    }
}

class QueryImpl : Query {
    override val aStr: String?
        get() = TODO("not implemented")
    override val aInt: Int?
        get() = TODO("not implemented")
    override val aBool: Boolean?
        get() = TODO("not implemented")
    override val aFloat: Float?
        get() = TODO("not implemented")
    override val aNonNull: String
        get() = TODO("not implemented")
    override val ListNullableStrings: List<String?>?
        get() = TODO("not implemented")
    override val ListNotNullStrings: List<String>?
        get() = TODO("not implemented")
    override val NonNullListNullableContent: List<String?>
        get() = TODO("not implemented")
    override val NonNullListNotNullContent: List<String>
        get() = TODO("not implemented")
    override val Another: Another?
        get() = TODO("not implemented")
    override val ListOfAnother: List<Another>?
        get() = TODO("not implemented")
    override val aScalar: SampleScalar
        get() = TODO("not implemented")
}