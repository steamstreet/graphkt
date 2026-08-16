package com.steamstreet.graphkt.server.execution

import kotlin.test.Test
import kotlin.test.fail

class GraphQLDocumentFuzzTest {
    private val fuzzLimits = GraphQLDocumentLimits(
        maxDocumentBytes = 2_048,
        maxTokens = 256,
        maxSyntaxNesting = 32,
        maxSelectionDepth = 24,
        maxSelectedFields = 256,
        maxAliases = 64,
        maxFragmentExpansions = 128,
        maxFragmentNesting = 24,
        maxVariableBytes = 2_048,
    )

    @Test
    fun arbitraryDocumentsOnlyProduceDocumentErrorsOrValidationResults() {
        val random = FuzzRandom(0x47524150)
        repeat(1_000) { caseIndex ->
            val length = random.nextInt(513)
            val source = buildString(length) {
                repeat(length) { append(FUZZ_CHARACTERS[random.nextInt(FUZZ_CHARACTERS.size)]) }
            }
            assertSafePipeline(source, "arbitrary-$caseIndex")
        }
    }

    @Test
    fun mutationsOfValidDocumentsOnlyProduceDocumentErrorsOrValidationResults() {
        val random = FuzzRandom(0x4B544D50)
        repeat(1_000) { caseIndex ->
            val seed = VALID_DOCUMENTS[random.nextInt(VALID_DOCUMENTS.size)]
            assertSafePipeline(mutate(seed, random), "mutation-$caseIndex")
        }
    }

    @Test
    fun adversarialDocumentsStopAtFiniteLimits() {
        val cases = listOf(
            "{".repeat(512),
            "[".repeat(512),
            "(".repeat(512),
            "\"" + "\\u{".repeat(256),
            "query Q(" + (0 until 200).joinToString(" ") { index -> "${'$'}v$index: [[Int!]!]!" } + ") { __typename }",
            "{ " + (0 until 200).joinToString(" ") { index -> "alias$index: node(id: \"$index\") { id }" } + " }",
            fragmentChain(200),
            "#" + "x".repeat(2_047),
            "{ field(value: \"\\u{FFFFFF}\") }",
            "{ field(value: -" + "9".repeat(1_000) + ") }",
        )

        cases.forEachIndexed { index, source -> assertSafePipeline(source, "adversarial-$index") }
    }

    private fun assertSafePipeline(source: String, caseName: String) {
        val document = try {
            GraphQLDocumentParser(fuzzLimits).parse(source)
        } catch (_: GraphQLDocumentException) {
            return
        } catch (failure: Throwable) {
            fail(unexpectedFailure(caseName, source, failure))
        }

        try {
            GraphQLDocumentValidator(testSchema(), fuzzLimits).validate(document, null)
        } catch (failure: Throwable) {
            fail(unexpectedFailure(caseName, source, failure))
        }
    }

    private fun unexpectedFailure(caseName: String, source: String, failure: Throwable): String {
        val escapedSource = source.take(240)
            .replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
        return "$caseName caused ${failure::class.simpleName}: ${failure.message}; input=$escapedSource"
    }
}

private fun mutate(seed: String, random: FuzzRandom): String {
    val result = StringBuilder(seed)
    repeat(1 + random.nextInt(12)) {
        when (random.nextInt(5)) {
            0 -> result.insert(random.nextInt(result.length + 1), FUZZ_CHARACTERS[random.nextInt(FUZZ_CHARACTERS.size)])
            1 -> if (result.isNotEmpty()) result.deleteAt(random.nextInt(result.length))
            2 -> if (result.isNotEmpty()) {
                result[random.nextInt(result.length)] = FUZZ_CHARACTERS[random.nextInt(FUZZ_CHARACTERS.size)]
            }

            3 -> if (result.isNotEmpty() && result.length < MAX_MUTATION_LENGTH) {
                val start = random.nextInt(result.length)
                val end = start + random.nextInt(result.length - start + 1)
                result.insert(random.nextInt(result.length + 1), result.substring(start, end))
            }

            else -> if (result.length > 1) {
                val first = random.nextInt(result.length)
                val second = random.nextInt(result.length)
                val value = result[first]
                result[first] = result[second]
                result[second] = value
            }
        }
        if (result.length > MAX_MUTATION_LENGTH) result.setLength(MAX_MUTATION_LENGTH)
    }
    return result.toString()
}

private fun fragmentChain(count: Int): String = buildString {
    append("{ node(id: \"1\") { ...F0 } }")
    repeat(count) { index ->
        append(" fragment F$index on Node { ")
        if (index + 1 == count) append("id") else append("...F${index + 1}")
        append(" }")
    }
}

private class FuzzRandom(seed: Int) {
    private var state: Int = seed

    fun nextInt(bound: Int): Int {
        require(bound > 0)
        var value = state
        value = value xor (value shl 13)
        value = value xor (value ushr 17)
        value = value xor (value shl 5)
        state = value
        return (value and Int.MAX_VALUE) % bound
    }
}

private const val MAX_MUTATION_LENGTH: Int = 1_024

private val VALID_DOCUMENTS: List<String> = listOf(
    "{ node(id: \"1\") { id ... on User { name } } }",
    "query Search(${'$'}term: String!, ${'$'}limit: Int = 10) { search(term: ${'$'}term, limit: ${'$'}limit) { __typename } }",
    "{ user(key: {email: \"test@example.com\"}) { id } }",
    "query Cached @trace(label: \"fuzz\") { node(id: \"1\") @cached { id } }",
    "{ search(term: \"x\", filter: {status: OPEN, tags: [\"a\", \"b\"]}) { ...Result } } fragment Result on SearchResult { score }",
    "{ node(id: \"1\") { id } } fragment Unused on User { name }",
    "{ node(id: \"1\") { id } } # comment\r\n",
    "{ search(term: \"\"\"first\n  second\"\"\") { __typename } }",
)

private val FUZZ_CHARACTERS: CharArray = charArrayOf(
    '{', '}', '[', ']', '(', ')', '!', '$', ':', '=', '@', '|', '&', '.', ',', '#',
    '"', '\\', '-', '+', '_', '0', '1', '9', 'A', 'F', 'Z', 'a', 'f', 'z',
    ' ', '\t', '\r', '\n', '\u0000', '\u001F', '\u007F', '\u00E9', '\uFEFF',
)
