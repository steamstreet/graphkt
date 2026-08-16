package com.steamstreet.graphkt.server

import com.steamstreet.graphkt.GraphQLResponseEnvelope
import com.steamstreet.graphkt.GraphQLResponseKind

/** A JSON response media type supported by the GraphQL-over-HTTP adapter. */
public enum class GraphQLHttpResponseMediaType(public val value: String) {
    GRAPHQL_RESPONSE_JSON("application/graphql-response+json"),
    JSON("application/json"),
}

/** Selects the supported response media type with the highest client preference. */
public fun negotiateGraphQLHttpResponseMediaType(acceptHeader: String?): GraphQLHttpResponseMediaType? {
    if (acceptHeader == null) return GraphQLHttpResponseMediaType.GRAPHQL_RESPONSE_JSON
    val ranges = acceptHeader.split(',').mapIndexedNotNull(::parseMediaRange)
    return GraphQLHttpResponseMediaType.entries
        .mapNotNull { mediaType -> mediaType.score(ranges)?.let { score -> mediaType to score } }
        .maxWithOrNull(
            compareBy<Pair<GraphQLHttpResponseMediaType, MediaScore>> { it.second.quality }
                .thenBy { it.second.specificity }
                .thenBy { if (it.first == GraphQLHttpResponseMediaType.GRAPHQL_RESPONSE_JSON) 1 else 0 },
        )
        ?.first
}

/** Returns true when a POST body uses UTF-8 JSON. */
public fun isSupportedGraphQLHttpRequestContentType(contentType: String?): Boolean {
    if (contentType == null) return false
    val parts = contentType.split(';')
    if (!parts.first().trim().equals("application/json", ignoreCase = true)) return false
    val charset = parts.drop(1)
        .mapNotNull { parameter ->
            val name = parameter.substringBefore('=', missingDelimiterValue = "").trim()
            if (!name.equals("charset", ignoreCase = true)) return@mapNotNull null
            parameter.substringAfter('=', missingDelimiterValue = "").trim().trim('"')
        }
        .lastOrNull()
    return charset == null || charset.equals("utf-8", ignoreCase = true)
}

/** Maps one GraphQL response phase to its GraphQL-over-HTTP status code. */
public fun GraphQLResponseEnvelope.graphQLHttpStatusCode(): Int = when (kind) {
    GraphQLResponseKind.DOCUMENT_ERROR -> 400
    GraphQLResponseKind.REQUEST_ERROR -> 422
    GraphQLResponseKind.EXECUTION_RESULT -> 200
}

private data class MediaRange(
    val type: String,
    val subtype: String,
    val quality: Double,
    val index: Int,
) {
    val specificity: Int = when {
        type == "*" -> 0
        subtype == "*" -> 1
        else -> 2
    }
}

private data class MediaScore(
    val quality: Double,
    val specificity: Int,
)

private fun parseMediaRange(index: Int, value: String): MediaRange? {
    val parts = value.split(';')
    val mediaType = parts.first().trim().lowercase()
    val type = mediaType.substringBefore('/', missingDelimiterValue = "")
    val subtype = mediaType.substringAfter('/', missingDelimiterValue = "")
    if (type.isEmpty() || subtype.isEmpty()) return null
    val quality = parts.drop(1)
        .firstOrNull { parameter -> parameter.substringBefore('=').trim().equals("q", ignoreCase = true) }
        ?.substringAfter('=', missingDelimiterValue = "")
        ?.trim()
        ?.toDoubleOrNull()
        ?.takeIf { it in 0.0..1.0 }
        ?: if (parts.drop(1).any { it.substringBefore('=').trim().equals("q", ignoreCase = true) }) 0.0 else 1.0
    return MediaRange(type, subtype, quality, index)
}

private fun GraphQLHttpResponseMediaType.score(ranges: List<MediaRange>): MediaScore? {
    val type = value.substringBefore('/')
    val subtype = value.substringAfter('/')
    val range = ranges
        .filter { candidate ->
            (candidate.type == "*" || candidate.type == type) &&
                (candidate.subtype == "*" || candidate.subtype == subtype)
        }
        .maxWithOrNull(
            compareBy<MediaRange> { it.specificity }
                .thenBy { it.quality }
                .thenBy { -it.index },
        )
        ?: return null
    return range.quality.takeIf { it > 0.0 }?.let { MediaScore(it, range.specificity) }
}
