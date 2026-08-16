package com.steamstreet.graphkt.server.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphQLDocumentValidatorTest {
    private val parser = GraphQLDocumentParser()

    @Test
    fun acceptsValidFieldsFragmentsArgumentsAndVariables() {
        val document = parser.parse(
            """
            query Search(${'$'}term: String!, ${'$'}limit: Int) {
                search(term: ${'$'}term, limit: ${'$'}limit, filter: {status: OPEN, tags: "kotlin"}) {
                    __typename
                    ... on User { id name }
                    ...ProductFields
                }
            }

            fragment ProductFields on Product {
                id
                title @include(if: true)
            }
            """.trimIndent(),
        )

        val result = GraphQLDocumentValidator(testSchema()).validate(document, "Search")

        assertTrue(result.isValid, result.errors.joinToString { it.message.orEmpty() })
        assertEquals("Search", result.operation?.name)
    }

    @Test
    fun rejectsUnknownFieldsMissingArgumentsAndInvalidSelectionShapes() {
        val document = parser.parse(
            """
            {
                node { missing }
                search { __typename }
                search(term: "x") { __typename { value } }
                node(id: "1")
            }
            """.trimIndent(),
        )

        val messages = GraphQLDocumentValidator(testSchema()).validate(document, null).messages()

        assertTrue(messages.any { "Field 'missing' does not exist" in it }, messages.toString())
        assertTrue(messages.any { "Required argument 'id' is missing" in it }, messages.toString())
        assertTrue(messages.any { "Required argument 'term' is missing" in it }, messages.toString())
        assertTrue(messages.any { "Leaf field '__typename' must not have a selection set" in it }, messages.toString())
        assertTrue(messages.any { "Field 'node' must have a selection set" in it }, messages.toString())
    }

    @Test
    fun rejectsFragmentCyclesImpossibleSpreadsAndUnusedFragments() {
        val document = parser.parse(
            """
            query Find(${'$'}id: ID!) {
                node(id: ${'$'}id) {
                    ...A
                    ... on Query { __typename }
                }
            }

            fragment A on Node { ...B }
            fragment B on Node { ...A }
            fragment NeverUsed on User { id }
            """.trimIndent(),
        )

        val messages = GraphQLDocumentValidator(testSchema()).validate(document, "Find").messages()

        assertTrue(messages.any { "Fragment cycle" in it }, messages.toString())
        assertTrue(messages.any { "cannot apply to type 'Node'" in it }, messages.toString())
        assertTrue(messages.any { "Fragment 'NeverUsed' is never used" in it }, messages.toString())
    }

    @Test
    fun enforcesVariableDefinitionAndUsageRules() {
        val document = parser.parse(
            """
            query Invalid(${'$'}nullable: String, ${'$'}unused: Int, ${'$'}wrong: User) {
                search(term: ${'$'}nullable) { __typename }
                node(id: ${'$'}missing) { id }
            }
            """.trimIndent(),
        )

        val messages = GraphQLDocumentValidator(testSchema()).validate(document, "Invalid").messages()

        assertTrue(messages.any { "must use an input type" in it }, messages.toString())
        assertTrue(messages.any { "cannot be used where 'String!' is required" in it }, messages.toString())
        assertTrue(messages.any { "Variable '${'$'}missing' is not defined" in it }, messages.toString())
        assertTrue(messages.any { "Variable '${'$'}unused' is never used" in it }, messages.toString())
        assertTrue(messages.any { "Variable '${'$'}wrong' is never used" in it }, messages.toString())
    }

    @Test
    fun permitsNullableVariableWhenTheArgumentHasADefault() {
        val document = parser.parse(
            """
            query Search(${'$'}term: String!, ${'$'}limit: Int) {
                search(term: ${'$'}term, limit: ${'$'}limit) { __typename }
            }
            """.trimIndent(),
        )

        val result = GraphQLDocumentValidator(testSchema()).validate(document, "Search")

        assertTrue(result.isValid, result.errors.joinToString { it.message.orEmpty() })
    }

    @Test
    fun validatesOneOfAndInputObjectLiterals() {
        val document = parser.parse(
            """
            {
                first: user(key: {}) { id }
                second: user(key: {id: null}) { id }
                third: user(key: {id: "1", email: "a@example.com"}) { id }
                fourth: search(term: "x", filter: {unknown: true}) { __typename }
            }
            """.trimIndent(),
        )

        val messages = GraphQLDocumentValidator(testSchema()).validate(document, null).messages()

        assertEquals(2, messages.count { "requires exactly one field" in it })
        assertTrue(messages.any { "requires a non-null field" in it }, messages.toString())
        assertTrue(messages.any { "Input field 'unknown' is not defined" in it }, messages.toString())
    }

    @Test
    fun appliesOperationLimitsAfterFragmentExpansion() {
        val document = parser.parse(
            """
            { node(id: "1") { alias: id ...Fields } }
            fragment Fields on Node { id }
            """.trimIndent(),
        )
        val limits = GraphQLDocumentLimits(
            maxSelectedFields = 2,
            maxAliases = 0,
            maxFragmentExpansions = 1,
        )

        val result = GraphQLDocumentValidator(testSchema(), limits).validate(document, null)

        assertFalse(result.isValid)
        assertTrue(result.messages().any { "alias limit" in it }, result.messages().toString())
    }

    @Test
    fun rejectsFieldConflictsIntroducedThroughFragments() {
        val document = parser.parse(
            """
            {
                ...First
                ...Second
            }
            fragment First on Query { result: node(id: "1") { id } }
            fragment Second on Query { result: node(id: "2") { id } }
            """.trimIndent(),
        )

        val messages = GraphQLDocumentValidator(testSchema()).validate(document, null).messages()

        assertTrue(messages.any { "Fields named 'result' conflict" in it }, messages.toString())
        assertTrue(messages.any { "different arguments" in it }, messages.toString())
    }

    @Test
    fun rejectsNestedConflictsAfterParentFieldsMerge() {
        val document = parser.parse(
            """
            {
                node(id: "1") { value: id }
                node(id: "1") { value: __typename }
            }
            """.trimIndent(),
        )

        val messages = GraphQLDocumentValidator(testSchema()).validate(document, null).messages()

        assertTrue(messages.any { "Fields named 'node.value' conflict" in it }, messages.toString())
    }

    @Test
    fun permitsDifferentFieldsOnMutuallyExclusiveObjectTypes() {
        val document = parser.parse(
            """
            {
                search(term: "kotlin") {
                    ... on User { label: name }
                    ... on Product { label: title }
                }
            }
            """.trimIndent(),
        )

        val result = GraphQLDocumentValidator(testSchema()).validate(document, null)

        assertTrue(result.isValid, result.messages().toString())
    }

    @Test
    fun permitsMergedArgumentsWithDifferentInputObjectFieldOrder() {
        val document = parser.parse(
            """
            {
                result: search(term: "kotlin", filter: {status: OPEN, tags: ["server"]}) { __typename }
                result: search(filter: {tags: ["server"], status: OPEN}, term: "kotlin") { __typename }
            }
            """.trimIndent(),
        )

        val result = GraphQLDocumentValidator(testSchema()).validate(document, null)

        assertTrue(result.isValid, result.messages().toString())
    }

    @Test
    fun requiresCompatibleResponseShapesOnMutuallyExclusiveObjectTypes() {
        val document = parser.parse(
            """
            {
                search(term: "kotlin") {
                    ... on User { label: name }
                    ... on Product { label: id }
                }
            }
            """.trimIndent(),
        )

        val messages = GraphQLDocumentValidator(testSchema()).validate(document, null).messages()

        assertTrue(messages.any { "Fields named 'search.label' conflict" in it }, messages.toString())
        assertTrue(messages.any { "incompatible types" in it }, messages.toString())
    }

    @Test
    fun validatesSchemaDefinedExecutableDirectives() {
        val document = parser.parse(
            """
            query Cached(${'$'}ttl: Int!) @trace(label: "request") {
                node(id: "1") @cached @cached(ttl: ${'$'}ttl) { id }
            }
            """.trimIndent(),
        )

        val result = GraphQLDocumentValidator(testSchema()).validate(document, "Cached")

        assertTrue(result.isValid, result.messages().toString())
    }

    @Test
    fun rejectsInvalidCustomDirectiveLocationsAndArguments() {
        val document = parser.parse(
            """
            mutation Invalid @cached(unknown: true) {
                rename(name: "Ada") { id }
            }
            """.trimIndent(),
        )

        val messages = GraphQLDocumentValidator(testSchema()).validate(document, "Invalid").messages()

        assertTrue(messages.any { "not valid at mutation" in it }, messages.toString())
        assertTrue(messages.any { "Argument 'unknown' is not defined on '@cached'" in it }, messages.toString())
    }

    @Test
    fun rejectsMissingDirectiveArgumentsAndRepeatedNonrepeatableDirectives() {
        val document = parser.parse(
            """
            {
                node(id: "1") @authorize @authorize(role: "admin") { id }
            }
            """.trimIndent(),
        )

        val messages = GraphQLDocumentValidator(testSchema()).validate(document, null).messages()

        assertTrue(messages.any { "Directive '@authorize' is not repeatable" in it }, messages.toString())
        assertTrue(messages.any { "Required argument 'role' is missing on '@authorize'" in it }, messages.toString())
    }
}

private fun DocumentValidationResult.messages(): List<String> = errors.map { it.message.orEmpty() }
