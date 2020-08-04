package com.steamstreet.steamql.samples.basic

import com.steamstreet.steamql.RequestSelection
import graphql.language.Field
import graphql.language.OperationDefinition
import graphql.parser.Parser
import kotlinx.serialization.ImplicitReflectionSerializer
import kotlinx.serialization.json.*
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
            val jsonResult = base.select(it)
            println(Json(JsonConfiguration.Stable).stringify(jsonResult))
        }
    }
}

class FieldRequestSelection(val field: Field) : RequestSelection {
    override val name: String = field.name
    override val children: List<RequestSelection>
        get() = this.field.selectionSet.selections.mapNotNull { it as? Field }.map { FieldRequestSelection(it) }
}

interface RequestContext {
    fun variable(name: String): JsonElement
    val selection: RequestSelection
}

fun Query._gql_name(field: RequestSelection): JsonElement = JsonPrimitive(name)
fun Query._gql_another(field: RequestSelection): JsonElement = another.select(field.children)
fun Query._gql_allOthers(field: RequestSelection): JsonElement = JsonArray(allOthers.map { it.select(field.children) })

fun Query.select(request: RequestContext): JsonElement {
    return JsonObject(request.selection.children.associate { field ->
        field.name to when (field.name) {
            "name" -> _gql_name(field)
            "another" -> _gql_another(field)
            "allOthers" -> _gql_allOthers(field)
            else -> JsonNull
        }
    })
}

fun Another.select(fields: List<RequestSelection>): JsonElement {
    val content = HashMap<String, JsonElement>()
    fields.forEach { field ->
        val element = when (field.name) {
            "age" -> JsonPrimitive(age)
            else -> JsonNull
        }
        content[field.name] = element
    }
    return JsonObject(content)
}

interface Another {
    val age: Int
}

interface Query {
    val name: String
    val another: Another
    val allOthers: List<Another>

    fun getSomething(str: String): Another
}

class QueryImpl : Query {
    override val name: String
        get() = "Creed Bratton"
    override val another: Another
        get() = AnotherImpl()

    override val allOthers: List<Another>
        get() = listOf(AnotherImpl(), AnotherImpl())

    override fun getSomething(str: String): Another {
        return AnotherImpl()
    }
}

class AnotherImpl : Another {
    override val age: Int
        get() = 43
}