package com.steamstreet.graphkt.server

import com.steamstreet.graphkt.GraphQLError
import graphql.language.EnumTypeDefinition
import graphql.language.EnumTypeExtensionDefinition
import graphql.language.FieldDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.Type
import graphql.language.TypeName
import graphql.language.UnionTypeDefinition
import graphql.parser.Parser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Builds selections for a corpus of documents with both the JVM [ServerRequestSelection] and the
 * common [RequestSelection.forOperation], and requires the two trees to be identical in every
 * observable respect: names, response names, type names, paths, parameters, input parameters,
 * variables, the paths of indexed copies, and the errors recorded through each node.
 *
 * The corpus is the edge-case documents in `parity/edge-documents.graphql` and a document set
 * generated from Vegasful's GraphQL schema in the shape of the documents its generated client
 * writes (a variable per argument, `__typename`, and `... on Type` fragments), so every field of
 * its API is selected, with and without aliases and with literal arguments.
 */
class OperationSelectionParityTest {
    private data class Case(val name: String, val query: String, val variables: JsonObject?)

    @Test
    fun edgeDocumentsMatchTheJvmSelection() {
        val cases = edgeCases()
        assertTrue(cases.size >= 30, "expected the whole edge corpus, found ${cases.size}")
        cases.forEach(::assertParity)
    }

    @Test
    fun documentsGeneratedFromTheVegasfulSchemaMatchTheJvmSelection() {
        val cases = SchemaDocuments(
            listOf("parity/vegasful-schema.graphql", "parity/vegasful-admin.graphql").map(::resource),
        ).cases()
        println("Vegasful schema parity corpus: ${cases.size} documents, ${cases.sumOf { it.query.length }} characters")
        assertTrue(cases.size > 100, "expected a corpus from the schema, found ${cases.size}")
        cases.forEach(::assertParity)
    }

    @Test
    fun invalidDocumentsAreRejectedByBothVersions() {
        listOf("", "   ", "{", "{ }", "query", "{ a(b: ) }", "{ a(b: 1 }", "fragment F on T { id }", "type T { a: Int }", "{ a } }", "{ a(b: 01) }")
            .forEach { query ->
                assertTrue(runCatching { jvmSelection(query, null, mutableListOf()) }.isFailure, "JVM accepted <$query>")
                assertTrue(
                    runCatching { RequestSelection.forOperation(query, null, mutableListOf()) }.exceptionOrNull()
                        is IllegalArgumentException,
                    "common accepted <$query>",
                )
            }
    }

    @Test
    fun operationNameSelectsAmongSeveralOperations() {
        val query = "query First { first { id } } query Second { second { id } } mutation Third { third { id } }"
        fun root(name: String?) = RequestOperation.parse(query, null, mutableListOf(), operationName = name)

        assertEquals(listOf("first"), root(null).selection.children.map { it.name })
        assertEquals(RequestOperationType.QUERY, root(null).type)
        assertEquals(listOf("second"), root("Second").selection.children.map { it.name })
        assertEquals(RequestOperationType.MUTATION, root("Third").type)
        assertTrue(runCatching { root("Missing") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun aDocumentThatStartsWithAFragmentHasNoOperationToSelect() {
        val query = "fragment F on T { id } query Named { a { ...F } }"
        assertTrue(runCatching { parseGraphQLOperation(query) }.isFailure)
        assertTrue(
            runCatching { RequestSelection.forOperation(query, null, mutableListOf()) }.exceptionOrNull()
                is IllegalArgumentException,
        )
        // A name still finds the operation, which the JVM version cannot.
        val selection = RequestSelection.forOperation(query, null, mutableListOf(), operationName = "Named")
        assertEquals(listOf("a"), selection.children.map { it.name })
    }

    /**
     * The JVM version has no model of named fragments: a spread is a field named for the fragment,
     * and asking for its children fails. The common version expands the fragment in place.
     */
    @Test
    fun namedFragmentsAreExpandedWhereTheJvmVersionDoesNotSupportThem() {
        val query = "query { entity { id ...Parts } } fragment Parts on Venue { name address }"
        val jvmChildren = jvmSelection(query, null, mutableListOf()).children.single().children
        assertEquals(listOf("id", "Parts"), jvmChildren.map { it.name })
        assertTrue(runCatching { jvmChildren.last().children }.exceptionOrNull() is NotImplementedError)

        val common = RequestSelection.forOperation(query, null, mutableListOf()).children.single().children
        assertEquals(listOf("id", "name", "address"), common.map { it.name })
        assertEquals(listOf(null, "Venue", "Venue"), common.map { it.typeName })
    }

    @Test
    fun nestedInlineFragmentsAreFlattenedWhereTheJvmVersionFails() {
        val query = "{ a { ... on T { x ... on U { y } } } }"
        val jvm = jvmSelection(query, null, mutableListOf()).children.single().children
        assertTrue(runCatching { jvm.last().children }.exceptionOrNull() is NotImplementedError)

        val common = RequestSelection.forOperation(query, null, mutableListOf()).children.single().children
        assertEquals(listOf("x", "y"), common.map { it.name })
        assertEquals(listOf("T", "U"), common.map { it.typeName })
    }

    @Test
    fun floatLiteralsRenderAsBigDecimalDoes() {
        listOf(
            "0.0", "-0.0", "1.5", "1.50", "100.0", "0.0001", "0.000001", "0.0000001", "1e3", "1E3", "1e+3", "1e-3",
            "1.0e10", "-2.5e-3", "1.5E+20", "12345.6789", "0.1e1", "10e-1", "1e0", "0e5", "123456789.123456789e5",
            "3.14159265358979323846", "5e-324", "1.7976931348623157e308", "0.00000000000000000001",
            "99999999999999999999.9999999999",
        ).forEach { literal ->
            assertEquals(java.math.BigDecimal(literal).toString(), bigDecimalString(literal), literal)
        }
    }

    private fun assertParity(case: Case) {
        val jvm = runCatching { jvmSelection(case.query, case.variables, mutableListOf()) }
        val jvmErrors = mutableListOf<GraphQLError>()
        val commonErrors = mutableListOf<GraphQLError>()
        val common = runCatching {
            RequestSelection.forOperation(case.query, case.variables, commonErrors)
        }

        // Every corpus document is valid, so a document that neither version parses is a corpus bug.
        assertTrue(jvm.isSuccess && common.isSuccess, "${case.name}: parse outcome: jvm=$jvm common=$common")
        val jvmRoot = jvmSelection(case.query, case.variables, jvmErrors)
        val expected = render(jvmRoot, jvmErrors)
        val actual = render(common.getOrThrow(), commonErrors)
        assertEquals(expected, actual, "${case.name}: trees differ")
        assertEquals(jvmErrors.map(::summary), commonErrors.map(::summary), "${case.name}: recorded errors differ")
    }

    private fun summary(error: GraphQLError) = Triple(error.message, error.path, error.extensions)

    private fun jvmSelection(query: String, variables: JsonObject?, errors: MutableList<GraphQLError>) =
        ServerRequestSelection(
            parent = null,
            variables = variables ?: emptyMap(),
            node = parseGraphQLOperation(query).selectionSet,
            errors = errors,
        )

    /**
     * Renders every observable of a selection tree. Each node also has an error recorded through
     * itself and through an indexed copy, which is how resolvers report failures.
     */
    private fun render(root: RequestSelection, errors: MutableList<GraphQLError>): String = buildString {
        fun visit(selection: RequestSelection, depth: Int) {
            val pad = "  ".repeat(depth)
            append(pad).append("name=").append(attempt { selection.name })
            append(" responseName=").append(attempt { selection.responseName })
            append(" typeName=").append(selection.typeName)
            append(" path=").append(selection.path)
            append(" appliesToVenue=").append(selection.appliesTo("Venue"))
            append('\n')
            append(pad).append("  parameters=").append(attempt { selection.parameters })
            append('\n')
            append(pad).append("  inputs=").append(attempt {
                selection.parameters.keys.map { it to selection.inputParameter(it).toString() } +
                    ("absent" to selection.inputParameter("absent").toString())
            })
            append('\n')
            append(pad).append("  variables=").append(
                listOf("v", "id", "s", "limit", "missing").map { selection.variable(it).toString() },
            )
            append('\n')
            selection.error(IllegalStateException("failure at ${selection.path}"))
            val indexed = selection.forIndex(2)
            append(pad).append("  indexedPath=").append(indexed.path)
            append(" indexedType=").append(indexed.typeName)
            append('\n')
            indexed.error(IllegalStateException("failure at indexed ${indexed.path}"))
            val children = attempt { selection.children }
            if (children is String) {
                append(pad).append("  children=").append(children).append('\n')
                return
            }
            @Suppress("UNCHECKED_CAST")
            (children as List<RequestSelection>).forEach { visit(it, depth + 1) }
            // The children of an indexed copy rebase their paths.
            indexed.children.forEach {
                append(pad).append("  indexedChild=").append(it.path).append(' ').append(it.typeName).append('\n')
            }
        }
        visit(root, 0)
        append("errors=").append(errors.map { it.path })
    }

    // Asking the root selection set for a field's name or arguments fails on both. The JVM version
    // fails with a ClassCastException for its arguments, so only the failure is compared.
    private fun attempt(block: () -> Any?): Any? = try {
        block()
    } catch (failure: Throwable) {
        "throws"
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResource(path)) { "Missing test resource $path" }.readText()

    private fun edgeCases(): List<Case> =
        resource("parity/edge-documents.graphql").split(Regex("^# ---$", RegexOption.MULTILINE)).map { block ->
            val lines = block.trim().lines()
            fun header(name: String) = lines.firstOrNull { it.startsWith("# $name:") }?.substringAfter("# $name:")?.trim()
            Case(
                name = header("name") ?: error("A document needs a name: $block"),
                query = lines.filterNot { it.startsWith("# name:") || it.startsWith("# variables:") }.joinToString("\n"),
                variables = header("variables")?.let { Json.parseToJsonElement(it).jsonObject },
            )
        }

    private class Variables {
        val definitions = mutableListOf<String>()
        val values = linkedMapOf<String, JsonElement>()
        fun add(name: String, type: String, value: JsonElement): String {
            var key = name
            var index = 1
            while (key in values) key = name + ++index
            definitions += "\$$key: $type"
            values[key] = value
            return key
        }
    }

    /**
     * Generates documents the way the generated client does, from a schema, so that every field of
     * the API is selected. Arguments become variables, whose values are generated from their types.
     */
    private inner class SchemaDocuments(schemas: List<String>) {
        private val definitions = schemas.flatMap { Parser().parseDocument(it).definitions }
        private val objects = definitions.filterIsInstance<ObjectTypeDefinition>().associateBy { it.name }
        private val interfaces = definitions.filterIsInstance<InterfaceTypeDefinition>().associateBy { it.name }
        private val unions = definitions.filterIsInstance<UnionTypeDefinition>().associateBy { it.name }
        private val inputs = definitions.filterIsInstance<InputObjectTypeDefinition>().associateBy { it.name }
        private val enums = definitions.filterIsInstance<EnumTypeDefinition>()
            .filterNot { it is EnumTypeExtensionDefinition }.associateBy { it.name }

        fun cases(): List<Case> = buildList {
            listOf("Query", "Mutation").forEach { root ->
                val rootType = objects.getValue(root)
                // One document for each root field, and each of the fields below the first level.
                rootType.fieldDefinitions.forEach { field ->
                    add(document("$root.${field.name}", root.lowercase(), listOf(field), depth = 4, aliased = false))
                    add(document("$root.${field.name} aliased", root.lowercase(), listOf(field), depth = 3, aliased = true))
                    add(literalDocument("$root.${field.name} literal", root.lowercase(), field))
                }
                // And the whole root at once, as a page that fetches several things does.
                add(document("$root all", root.lowercase(), rootType.fieldDefinitions, depth = 2, aliased = false))
            }
            // Every object and interface type is also reached directly, so that no field is missed
            // because of the depth limit.
            (objects.values.filter { it.name !in setOf("Query", "Mutation") }).forEach { type ->
                add(typeDocument(type.name, type.fieldDefinitions))
            }
            interfaces.values.forEach { add(typeDocument(it.name, it.fieldDefinitions)) }
        }

        private fun typeDocument(name: String, fields: List<FieldDefinition>): Case {
            // A synthetic root field `x` of the type, so the type's own fields are selected.
            val variables = Variables()
            val body = StringBuilder("query Q$name")
            val selection = StringBuilder()
            fields.forEach { field -> selectField(selection, field, variables, 2, 1, false) }
            body.append(variableDefinitions(variables)).append(" {\n  x {\n").append(selection).append("  }\n}")
            return Case("type $name", body.toString(), JsonObject(variables.values))
        }

        private fun document(name: String, kind: String, fields: List<FieldDefinition>, depth: Int, aliased: Boolean): Case {
            val variables = Variables()
            val selection = StringBuilder()
            fields.forEach { selectField(selection, it, variables, depth, 1, aliased) }
            val query = "$kind Q${name.hashCode().toUInt()}${variableDefinitions(variables)} {\n$selection}"
            return Case(name, query, JsonObject(variables.values).takeIf { variables.values.isNotEmpty() })
        }

        /** Arguments written as literals instead of variables. */
        private fun literalDocument(name: String, kind: String, field: FieldDefinition): Case {
            val arguments = field.inputValueDefinitions.joinToString(", ") { "${it.name}: ${literal(it.type, 0)}" }
            val call = if (arguments.isEmpty()) "" else "($arguments)"
            val body = leafSelection(field.type, 2)
            return Case(name, "$kind {\n  ${field.name}$call$body\n}", null)
        }

        private fun leafSelection(type: Type<*>, depth: Int): String {
            val name = typeName(type)
            val fields = (objects[name]?.fieldDefinitions ?: interfaces[name]?.fieldDefinitions)
            return if (fields == null) "" else " {\n    __typename\n" +
                fields.take(depth + 2).joinToString("") { "    ${it.name}\n".takeIf { _ -> isLeaf(it.type) } ?: "" } + "  }"
        }

        private fun variableDefinitions(variables: Variables) =
            if (variables.definitions.isEmpty()) "" else "(" + variables.definitions.joinToString(",") + ")"

        private fun selectField(
            out: StringBuilder, field: FieldDefinition, variables: Variables, depth: Int, level: Int, aliased: Boolean,
        ) {
            val indent = "  ".repeat(level)
            val arguments = if (field.inputValueDefinitions.isEmpty()) "" else
                field.inputValueDefinitions.joinToString(", ", "(", ")") { argument ->
                    "${argument.name}: \$" + variables.add(argument.name, render(argument.type), value(argument.type, 0))
                }
            val alias = if (aliased) "a_${field.name}: " else ""
            val typeName = typeName(field.type)
            val objectFields = objects[typeName]?.fieldDefinitions ?: interfaces[typeName]?.fieldDefinitions
            val union = unions[typeName]
            if (objectFields == null && union == null) {
                out.append(indent).append(alias).append(field.name).append(arguments).append('\n')
                return
            }
            out.append(indent).append(alias).append(field.name).append(arguments).append(" {\n")
            if (depth > 1) {
                out.append(indent).append("  __typename\n")
                objectFields?.forEach { selectField(out, it, variables, depth - 1, level + 1, aliased) }
                // Interfaces and unions select their concrete types in fragments.
                val concrete = when {
                    union != null -> union.memberTypes.map { typeName(it) }
                    interfaces.containsKey(typeName) ->
                        objects.values.filter { type -> type.implements.any { typeName(it) == typeName } }.map { it.name }
                    else -> emptyList()
                }
                concrete.forEach { name ->
                    out.append(indent).append("  ... on ").append(name).append(" {\n")
                    objects[name]?.fieldDefinitions?.forEach { selectField(out, it, variables, depth - 1, level + 2, aliased) }
                    out.append(indent).append("    __typename\n")
                    out.append(indent).append("  }\n")
                }
            } else {
                out.append(indent).append("  __typename\n")
            }
            out.append(indent).append("}\n")
        }

        private fun isLeaf(type: Type<*>): Boolean = typeName(type).let {
            it !in objects && it !in interfaces && it !in unions
        }

        private fun typeName(type: Type<*>): String = when (type) {
            is NonNullType -> typeName(type.type)
            is ListType -> typeName(type.type)
            is TypeName -> type.name
            else -> error("Unexpected type $type")
        }

        private fun render(type: Type<*>): String = when (type) {
            is NonNullType -> render(type.type) + "!"
            is ListType -> "[" + render(type.type) + "]"
            is TypeName -> type.name
            else -> error("Unexpected type $type")
        }

        private fun value(type: Type<*>, depth: Int): JsonElement = when (type) {
            is NonNullType -> value(type.type, depth)
            is ListType -> JsonArray(listOf(value(type.type, depth), value(type.type, depth)))
            is TypeName -> when (val name = type.name) {
                "Int" -> JsonPrimitive(42)
                "Float" -> JsonPrimitive(2.5)
                "Boolean" -> JsonPrimitive(true)
                "String", "ID" -> JsonPrimitive("a \"value\" with \\ and \u00e9")
                in enums -> JsonPrimitive(enums.getValue(name).enumValueDefinitions.first().name)
                in inputs -> if (depth > 3) JsonNull else buildJsonObject {
                    inputs.getValue(name).inputValueDefinitions.forEach { put(it.name, value(it.type, depth + 1)) }
                }
                else -> JsonPrimitive("2026-10-09T10:15:30Z")
            }
            else -> error("Unexpected type $type")
        }

        /** The same values as GraphQL literals. */
        private fun literal(type: Type<*>, depth: Int): String = when (type) {
            is NonNullType -> literal(type.type, depth)
            is ListType -> "[" + literal(type.type, depth) + ", " + literal(type.type, depth) + "]"
            is TypeName -> when (val name = type.name) {
                "Int" -> "42"
                "Float" -> "2.5e0"
                "Boolean" -> "true"
                "String", "ID" -> "\"a \\\"value\\\" with \\\\ and \\u00e9\""
                in enums -> enums.getValue(name).enumValueDefinitions.first().name
                in inputs -> if (depth > 3) "null" else
                    inputs.getValue(name).inputValueDefinitions.joinToString(", ", "{", "}") {
                        "${it.name}: ${literal(it.type, depth + 1)}"
                    }
                else -> "\"2026-10-09T10:15:30Z\""
            }
            else -> error("Unexpected type $type")
        }
    }
}
