package com.steamstreet.graphkt.server.execution

/** Limits applied before resolver creation or execution. */
public data class GraphQLDocumentLimits(
    public val maxDocumentBytes: Int = 1_048_576,
    public val maxTokens: Int = 50_000,
    public val maxSyntaxNesting: Int = 128,
    public val maxSelectionDepth: Int = 64,
    public val maxSelectedFields: Int = 10_000,
    public val maxAliases: Int = 1_000,
    public val maxFragmentExpansions: Int = 10_000,
    public val maxFragmentNesting: Int = 128,
    public val maxVariableBytes: Int = 1_048_576,
) {
    init {
        require(maxDocumentBytes > 0) { "maxDocumentBytes must be greater than zero" }
        require(maxTokens > 0) { "maxTokens must be greater than zero" }
        require(maxSyntaxNesting > 0) { "maxSyntaxNesting must be greater than zero" }
        require(maxSelectionDepth > 0) { "maxSelectionDepth must be greater than zero" }
        require(maxSelectedFields > 0) { "maxSelectedFields must be greater than zero" }
        require(maxAliases >= 0) { "maxAliases must not be negative" }
        require(maxFragmentExpansions > 0) { "maxFragmentExpansions must be greater than zero" }
        require(maxFragmentNesting > 0) { "maxFragmentNesting must be greater than zero" }
        require(maxVariableBytes > 0) { "maxVariableBytes must be greater than zero" }
    }
}
