package com.steamstreet.graphkt.server

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.GraphQLPathSegment
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Converts a resolver failure into the error that a selection built by
 * [RequestSelection.forRootField], or the JVM `ServerRequestSelection`, records.
 */
public fun interface GraphQLResolverErrorFactory {
    public fun create(failure: Throwable, path: List<GraphQLPathSegment>): GraphQLError

    public companion object {
        /** Records a generic message, so that no exception detail reaches the response. The default. */
        public val Generic: GraphQLResolverErrorFactory = GraphQLResolverErrorFactory { _, path ->
            GraphQLError("Internal Server Error", path = path)
        }

        /**
         * Records the exception message and, in the `stacktrace` extension, its stack trace, which is
         * what 2.x recorded for every failure. Use it only where the errors never reach an untrusted
         * client, such as in-process rendering or an internal service.
         *
         * The stack trace is the platform's own rendering of [Throwable.stackTraceToString], so a
         * Kotlin/Native trace differs in form from a JVM trace.
         */
        public val WithExceptionDetails: GraphQLResolverErrorFactory = GraphQLResolverErrorFactory { failure, path ->
            GraphQLError(
                message = failure.message ?: "Internal Server Error",
                path = path,
                extensions = buildJsonObject { put("stacktrace", failure.stackTraceToString()) },
            )
        }
    }
}
