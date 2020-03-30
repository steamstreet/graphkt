package com.steamstreet.steamql.generator

import org.gradle.api.Project

const val EXTENSION_NAME = "GraphQL"

/**
 * Configuration for the graphQL plugin
 */
open class GraphQLExtension {
    var schema: String = ""
    var basePackage: String = ""
}

internal fun Project.graphQL(): GraphQLExtension = extensions.getByName(EXTENSION_NAME) as? GraphQLExtension
        ?: throw IllegalStateException("$EXTENSION_NAME is not the correct type")
