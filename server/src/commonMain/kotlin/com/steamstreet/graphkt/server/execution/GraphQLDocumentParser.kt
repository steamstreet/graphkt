package com.steamstreet.graphkt.server.execution

internal class GraphQLDocumentParser(
    private val limits: GraphQLDocumentLimits = GraphQLDocumentLimits(),
) {
    fun parse(source: String): ExecutableDocument {
        checkDocumentSize(source)
        return Parser(Lexer(source, limits.maxTokens), limits.maxSyntaxNesting).parseDocument()
    }

    private fun checkDocumentSize(source: String) {
        if (source.length > limits.maxDocumentBytes || source.encodeToByteArray().size > limits.maxDocumentBytes) {
            throw GraphQLDocumentLimitException(
                "GraphQL document exceeds the ${limits.maxDocumentBytes}-byte limit",
            )
        }
    }
}

private class Parser(
    private val lexer: Lexer,
    private val maxSyntaxNesting: Int,
) {
    private var token: Token = lexer.nextToken()
    private var syntaxNesting = 0

    fun parseDocument(): ExecutableDocument {
        val operations = mutableListOf<OperationDefinition>()
        val fragments = mutableListOf<FragmentDefinition>()

        while (token.kind != TokenKind.EOF) {
            when {
                token.isPunctuator("{") -> operations += parseAnonymousOperation()
                token.isName("fragment") -> fragments += parseFragmentDefinition()
                token.isName("query") || token.isName("mutation") || token.isName("subscription") -> {
                    operations += parseOperationDefinition()
                }

                else -> syntaxError("Expected an operation or fragment definition")
            }
        }

        if (operations.isEmpty() && fragments.isEmpty()) {
            syntaxError("GraphQL document is empty")
        }

        return ExecutableDocument(operations, fragments)
    }

    private fun parseAnonymousOperation(): OperationDefinition {
        val location = token.location
        return OperationDefinition(
            type = OperationType.QUERY,
            name = null,
            variables = emptyList(),
            directives = emptyList(),
            selections = parseSelectionSet(),
            location = location,
        )
    }

    private fun parseOperationDefinition(): OperationDefinition {
        val operationToken = expect(TokenKind.NAME)
        val operationType = when (operationToken.value) {
            "query" -> OperationType.QUERY
            "mutation" -> OperationType.MUTATION
            "subscription" -> OperationType.SUBSCRIPTION
            else -> syntaxError("Unsupported operation type '${operationToken.value}'", operationToken.location)
        }
        val name = if (token.kind == TokenKind.NAME) consume().value else null
        val variables = if (token.isPunctuator("(")) parseVariableDefinitions() else emptyList()
        val directives = parseDirectives()
        val selections = parseSelectionSet()

        return OperationDefinition(operationType, name, variables, directives, selections, operationToken.location)
    }

    private fun parseFragmentDefinition(): FragmentDefinition {
        val location = expectName("fragment").location
        val nameToken = expect(TokenKind.NAME)
        if (nameToken.value == "on") {
            syntaxError("Fragment name cannot be 'on'", nameToken.location)
        }
        expectName("on")
        val typeCondition = expect(TokenKind.NAME).value
        val directives = parseDirectives()
        val selections = parseSelectionSet()
        return FragmentDefinition(nameToken.value, typeCondition, directives, selections, location)
    }

    private fun parseVariableDefinitions(): List<VariableDefinition> {
        val location = token.location
        return nested(location) {
            expectPunctuator("(")
            if (token.isPunctuator(")")) syntaxError("Variable definitions cannot be empty")

            buildList {
                while (!token.isPunctuator(")")) {
                    add(parseVariableDefinition())
                }
                expectPunctuator(")")
            }
        }
    }

    private fun parseVariableDefinition(): VariableDefinition {
        val location = expectPunctuator("$").location
        val name = expect(TokenKind.NAME).value
        expectPunctuator(":")
        val type = parseTypeReference()
        val defaultValue = if (token.isPunctuator("=")) {
            consume()
            parseValue(allowVariable = false)
        } else {
            null
        }
        val directives = parseDirectives()
        return VariableDefinition(name, type, defaultValue, directives, location)
    }

    private fun parseTypeReference(): TypeReference {
        val baseType = if (token.isPunctuator("[")) {
            val location = token.location
            nested(location) {
                consume()
                val element = parseTypeReference()
                expectPunctuator("]")
                TypeReference.ListType(element)
            }
        } else {
            TypeReference.Named(expect(TokenKind.NAME).value)
        }

        return if (token.isPunctuator("!")) {
            consume()
            TypeReference.NonNull(baseType)
        } else {
            baseType
        }
    }

    private fun parseSelectionSet(): List<Selection> {
        val location = token.location
        return nested(location) {
            expectPunctuator("{")
            if (token.isPunctuator("}")) syntaxError("Selection set cannot be empty")

            buildList {
                while (!token.isPunctuator("}")) {
                    add(parseSelection())
                }
                expectPunctuator("}")
            }
        }
    }

    private fun parseSelection(): Selection = if (token.kind == TokenKind.SPREAD) {
        parseFragmentSelection()
    } else {
        parseFieldSelection()
    }

    private fun parseFieldSelection(): FieldSelection {
        val firstName = expect(TokenKind.NAME)
        val alias: String?
        val name: String
        if (token.isPunctuator(":")) {
            consume()
            alias = firstName.value
            name = expect(TokenKind.NAME).value
        } else {
            alias = null
            name = firstName.value
        }

        val arguments = if (token.isPunctuator("(")) parseArguments() else emptyList()
        val directives = parseDirectives()
        val selections = if (token.isPunctuator("{")) parseSelectionSet() else emptyList()
        return FieldSelection(alias, name, arguments, directives, selections, firstName.location)
    }

    private fun parseFragmentSelection(): Selection {
        val location = expect(TokenKind.SPREAD).location
        if (token.isName("on") || token.isPunctuator("@") || token.isPunctuator("{")) {
            val typeCondition = if (token.isName("on")) {
                consume()
                expect(TokenKind.NAME).value
            } else {
                null
            }
            val directives = parseDirectives()
            return InlineFragment(typeCondition, directives, parseSelectionSet(), location)
        }

        val name = expect(TokenKind.NAME).value
        return FragmentSpread(name, parseDirectives(), location)
    }

    private fun parseArguments(): List<Argument> {
        val location = token.location
        return nested(location) {
            expectPunctuator("(")
            if (token.isPunctuator(")")) syntaxError("Arguments cannot be empty")

            buildList {
                while (!token.isPunctuator(")")) {
                    val nameToken = expect(TokenKind.NAME)
                    expectPunctuator(":")
                    add(Argument(nameToken.value, parseValue(allowVariable = true), nameToken.location))
                }
                expectPunctuator(")")
            }
        }
    }

    private fun parseDirectives(): List<Directive> = buildList {
        while (token.isPunctuator("@")) {
            val location = consume().location
            val name = expect(TokenKind.NAME).value
            val arguments = if (token.isPunctuator("(")) parseArguments() else emptyList()
            add(Directive(name, arguments, location))
        }
    }

    private fun parseValue(allowVariable: Boolean): Value = when (token.kind) {
        TokenKind.INT -> Value.IntValue(consume().value)
        TokenKind.FLOAT -> Value.FloatValue(consume().value)
        TokenKind.STRING -> Value.StringValue(consume().value)
        TokenKind.NAME -> when (val value = consume().value) {
            "true" -> Value.BooleanValue(true)
            "false" -> Value.BooleanValue(false)
            "null" -> Value.NullValue
            else -> Value.EnumValue(value)
        }

        TokenKind.PUNCTUATOR -> when {
            token.isPunctuator("$") -> {
                val location = consume().location
                if (!allowVariable) syntaxError("Variables are not allowed in constant values", location)
                Value.Variable(expect(TokenKind.NAME).value)
            }

            token.isPunctuator("[") -> parseListValue(allowVariable)
            token.isPunctuator("{") -> parseObjectValue(allowVariable)
            else -> syntaxError("Expected a GraphQL value")
        }

        else -> syntaxError("Expected a GraphQL value")
    }

    private fun parseListValue(allowVariable: Boolean): Value.ListValue {
        val location = token.location
        return nested(location) {
            expectPunctuator("[")
            val values = buildList {
                while (!token.isPunctuator("]")) add(parseValue(allowVariable))
            }
            expectPunctuator("]")
            Value.ListValue(values)
        }
    }

    private fun parseObjectValue(allowVariable: Boolean): Value.ObjectValue {
        val location = token.location
        return nested(location) {
            expectPunctuator("{")
            val fields = buildList {
                while (!token.isPunctuator("}")) {
                    val nameToken = expect(TokenKind.NAME)
                    expectPunctuator(":")
                    add(ObjectField(nameToken.value, parseValue(allowVariable), nameToken.location))
                }
            }
            expectPunctuator("}")
            Value.ObjectValue(fields)
        }
    }

    private inline fun <T> nested(location: SourceLocation, block: () -> T): T {
        syntaxNesting += 1
        if (syntaxNesting > maxSyntaxNesting) {
            throw GraphQLDocumentLimitException(
                "GraphQL document exceeds the syntax-nesting limit of $maxSyntaxNesting",
                location,
            )
        }
        return try {
            block()
        } finally {
            syntaxNesting -= 1
        }
    }

    private fun expectName(value: String): Token {
        val result = expect(TokenKind.NAME)
        if (result.value != value) syntaxError("Expected '$value'", result.location)
        return result
    }

    private fun expectPunctuator(value: String): Token {
        if (!token.isPunctuator(value)) syntaxError("Expected '$value'")
        return consume()
    }

    private fun expect(kind: TokenKind): Token {
        if (token.kind != kind) syntaxError("Expected ${kind.description}")
        return consume()
    }

    private fun consume(): Token = token.also { token = lexer.nextToken() }

    private fun syntaxError(message: String, location: SourceLocation = token.location): Nothing {
        throw GraphQLDocumentException(message, location)
    }
}

private enum class TokenKind(val description: String) {
    NAME("a name"),
    INT("an integer"),
    FLOAT("a float"),
    STRING("a string"),
    PUNCTUATOR("a punctuator"),
    SPREAD("'...'"),
    EOF("the end of the document"),
}

private data class Token(
    val kind: TokenKind,
    val value: String,
    val location: SourceLocation,
) {
    fun isName(value: String): Boolean = kind == TokenKind.NAME && this.value == value
    fun isPunctuator(value: String): Boolean = kind == TokenKind.PUNCTUATOR && this.value == value
}

private class Lexer(
    private val source: String,
    private val maxTokens: Int,
) {
    private var offset = 0
    private var line = 1
    private var column = 1
    private var tokenCount = 0

    fun nextToken(): Token {
        skipIgnored()
        val location = location()
        if (offset >= source.length) return Token(TokenKind.EOF, "", location)

        val token = when (val character = source[offset]) {
            in punctuators -> {
                advance()
                Token(TokenKind.PUNCTUATOR, character.toString(), location)
            }

            '.' -> readSpread(location)
            '"' -> readString(location)
            '-', in '0'..'9' -> readNumber(location)
            else -> if (character.isNameStart()) {
                readName(location)
            } else {
                throw GraphQLDocumentException("Unexpected character '$character'", location)
            }
        }

        tokenCount += 1
        if (tokenCount > maxTokens) {
            throw GraphQLDocumentLimitException(
                "GraphQL document exceeds the token limit of $maxTokens",
                location,
            )
        }
        return token
    }

    private fun skipIgnored() {
        while (offset < source.length) {
            when (source[offset]) {
                '\uFEFF', '\t', ' ', ',', '\n', '\r' -> advance()
                '#' -> {
                    while (offset < source.length && source[offset] != '\n' && source[offset] != '\r') {
                        advance()
                    }
                }

                else -> return
            }
        }
    }

    private fun readSpread(location: SourceLocation): Token {
        if (!source.startsWith("...", offset)) {
            throw GraphQLDocumentException("Expected '...'", location)
        }
        repeat(3) { advance() }
        return Token(TokenKind.SPREAD, "...", location)
    }

    private fun readName(location: SourceLocation): Token {
        val start = offset
        advance()
        while (offset < source.length && source[offset].isNameContinue()) advance()
        return Token(TokenKind.NAME, source.substring(start, offset), location)
    }

    private fun readNumber(location: SourceLocation): Token {
        val start = offset
        if (source[offset] == '-') advance()
        if (offset >= source.length) throw GraphQLDocumentException("Incomplete number", location)

        if (source[offset] == '0') {
            advance()
            if (offset < source.length && source[offset].isDigit()) {
                throw GraphQLDocumentException("Number cannot contain a leading zero", location)
            }
        } else {
            if (source[offset] !in '1'..'9') throw GraphQLDocumentException("Invalid number", location)
            while (offset < source.length && source[offset].isDigit()) advance()
        }

        var isFloat = false
        if (offset < source.length && source[offset] == '.') {
            isFloat = true
            advance()
            if (offset >= source.length || !source[offset].isDigit()) {
                throw GraphQLDocumentException("Float requires a digit after the decimal point", location)
            }
            while (offset < source.length && source[offset].isDigit()) advance()
        }

        if (offset < source.length && source[offset] in charArrayOf('e', 'E')) {
            isFloat = true
            advance()
            if (offset < source.length && source[offset] in charArrayOf('+', '-')) advance()
            if (offset >= source.length || !source[offset].isDigit()) {
                throw GraphQLDocumentException("Float exponent requires a digit", location)
            }
            while (offset < source.length && source[offset].isDigit()) advance()
        }

        if (offset < source.length && (source[offset].isNameStart() || source[offset] == '.')) {
            throw GraphQLDocumentException("Invalid character after number", location)
        }

        return Token(
            if (isFloat) TokenKind.FLOAT else TokenKind.INT,
            source.substring(start, offset),
            location,
        )
    }

    private fun readString(location: SourceLocation): Token {
        if (source.startsWith("\"\"\"", offset)) return readBlockString(location)

        advance()
        val value = StringBuilder()
        while (offset < source.length) {
            when (val character = advance()) {
                '"' -> return Token(TokenKind.STRING, value.toString(), location)
                '\\' -> value.append(readEscape(location))
                '\n', '\r' -> throw GraphQLDocumentException("String cannot contain a line terminator", location)
                else -> {
                    if (character.code < 0x20) {
                        throw GraphQLDocumentException("String cannot contain a control character", location)
                    }
                    value.append(character)
                }
            }
        }
        throw GraphQLDocumentException("Unterminated string", location)
    }

    private fun readEscape(location: SourceLocation): String {
        if (offset >= source.length) throw GraphQLDocumentException("Unterminated string escape", location)
        return when (val escaped = advance()) {
            '"', '\\', '/' -> escaped.toString()
            'b' -> "\b"
            'f' -> "\u000C"
            'n' -> "\n"
            'r' -> "\r"
            't' -> "\t"
            'u' -> readUnicodeEscape(location)
            else -> throw GraphQLDocumentException("Unknown string escape '\\$escaped'", location)
        }
    }

    private fun readUnicodeEscape(location: SourceLocation): String {
        if (offset < source.length && source[offset] == '{') {
            advance()
            var value = 0
            var digits = 0
            while (offset < source.length && source[offset] != '}') {
                val digit = advance().digitToIntOrNull(16)
                    ?: throw GraphQLDocumentException("Invalid Unicode escape", location)
                value = value * 16 + digit
                digits += 1
                if (digits > 6) throw GraphQLDocumentException("Unicode escape is too long", location)
            }
            if (digits == 0 || offset >= source.length) {
                throw GraphQLDocumentException("Incomplete Unicode escape", location)
            }
            advance()
            return codePointToString(value, location)
        }

        val leading = readFourHexDigits(location)
        if (leading in 0xD800..0xDBFF) {
            if (!source.startsWith("\\u", offset)) {
                throw GraphQLDocumentException("Leading surrogate requires a trailing surrogate", location)
            }
            advance()
            advance()
            val trailing = readFourHexDigits(location)
            if (trailing !in 0xDC00..0xDFFF) {
                throw GraphQLDocumentException("Invalid trailing surrogate", location)
            }
            val value = (leading - 0xD800) * 0x400 + (trailing - 0xDC00) + 0x10000
            return codePointToString(value, location)
        }
        return codePointToString(leading, location)
    }

    private fun readFourHexDigits(location: SourceLocation): Int {
        if (offset + 4 > source.length) throw GraphQLDocumentException("Incomplete Unicode escape", location)
        var value = 0
        repeat(4) {
            val character = advance()
            val digit = character.digitToIntOrNull(16)
                ?: throw GraphQLDocumentException("Invalid Unicode escape", location)
            value = value * 16 + digit
        }
        return value
    }

    private fun codePointToString(value: Int, location: SourceLocation): String {
        if (value < 0 || value > 0x10FFFF || value in 0xD800..0xDFFF) {
            throw GraphQLDocumentException("Unicode escape is not a scalar value", location)
        }
        if (value <= 0xFFFF) return value.toChar().toString()

        val supplementary = value - 0x10000
        val leading = (0xD800 + supplementary / 0x400).toChar()
        val trailing = (0xDC00 + supplementary % 0x400).toChar()
        return "$leading$trailing"
    }

    private fun readBlockString(location: SourceLocation): Token {
        repeat(3) { advance() }
        val rawValue = StringBuilder()
        while (offset < source.length) {
            when {
                source.startsWith("\\\"\"\"", offset) -> {
                    advance()
                    repeat(3) { advance() }
                    rawValue.append("\"\"\"")
                }

                source.startsWith("\"\"\"", offset) -> {
                    repeat(3) { advance() }
                    return Token(TokenKind.STRING, normalizeBlockString(rawValue.toString()), location)
                }

                source[offset] == '\n' || source[offset] == '\r' -> {
                    advance()
                    rawValue.append('\n')
                }

                else -> rawValue.append(advance())
            }
        }
        throw GraphQLDocumentException("Unterminated block string", location)
    }

    private fun advance(): Char {
        val character = source[offset++]
        when (character) {
            '\r' -> {
                if (offset < source.length && source[offset] == '\n') offset += 1
                line += 1
                column = 1
            }

            '\n' -> {
                line += 1
                column = 1
            }

            else -> column += 1
        }
        return character
    }

    private fun location(): SourceLocation = SourceLocation(offset, line, column)

    private companion object {
        val punctuators: CharArray = charArrayOf('!', '$', '&', '(', ')', ':', '=', '@', '[', ']', '{', '|', '}')
    }
}

private fun Char.isNameStart(): Boolean = this == '_' || this in 'A'..'Z' || this in 'a'..'z'

private fun Char.isNameContinue(): Boolean = isNameStart() || isDigit()

private fun normalizeBlockString(rawValue: String): String {
    val lines = rawValue.split('\n').toMutableList()
    val commonIndent = lines.drop(1)
        .filter { it.any { character -> character != ' ' && character != '\t' } }
        .minOfOrNull { line -> line.indexOfFirst { it != ' ' && it != '\t' } }
        ?: 0

    for (index in 1 until lines.size) {
        lines[index] = lines[index].drop(commonIndent)
    }
    while (lines.firstOrNull()?.isBlank() == true) lines.removeAt(0)
    while (lines.lastOrNull()?.isBlank() == true) lines.removeAt(lines.lastIndex)
    return lines.joinToString("\n")
}
