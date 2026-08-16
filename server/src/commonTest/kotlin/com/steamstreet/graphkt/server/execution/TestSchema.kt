package com.steamstreet.graphkt.server.execution

import com.steamstreet.graphkt.OptionalInput
import kotlinx.serialization.json.JsonPrimitive

internal fun testSchema(): GraphQLSchemaDefinition = GraphQLSchemaDefinition(
    queryType = "Query",
    mutationType = "Mutation",
    directives = listOf(
        GraphQLDirectiveDefinition(
            name = "trace",
            arguments = listOf(
                GraphQLInputValueDefinition("label", GraphQLTypeRef.Named("String")),
            ),
            locations = setOf(GraphQLDirectiveLocation.QUERY, GraphQLDirectiveLocation.FIELD),
        ),
        GraphQLDirectiveDefinition(
            name = "cached",
            arguments = listOf(
                GraphQLInputValueDefinition(
                    "ttl",
                    GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("Int")),
                    OptionalInput.Present(JsonPrimitive(60)),
                ),
            ),
            repeatable = true,
            locations = setOf(GraphQLDirectiveLocation.FIELD),
        ),
        GraphQLDirectiveDefinition(
            name = "authorize",
            arguments = listOf(
                GraphQLInputValueDefinition(
                    "role",
                    GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("String")),
                ),
            ),
            locations = setOf(GraphQLDirectiveLocation.FIELD),
        ),
    ),
    types = listOf(
        GraphQLScalarType("Boolean"),
        GraphQLScalarType("Float"),
        GraphQLScalarType("ID"),
        GraphQLScalarType("Int"),
        GraphQLScalarType("String"),
        GraphQLScalarType("Date"),
        GraphQLEnumType("Status", setOf("OPEN", "CLOSED")),
        GraphQLInputObjectType(
            name = "Filter",
            fields = listOf(
                GraphQLInputValueDefinition("status", GraphQLTypeRef.Named("Status")),
                GraphQLInputValueDefinition(
                    "limit",
                    GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("Int")),
                    OptionalInput.Present(JsonPrimitive(20)),
                ),
                GraphQLInputValueDefinition(
                    "tags",
                    GraphQLTypeRef.ListType(GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("String"))),
                ),
            ),
        ),
        GraphQLInputObjectType(
            name = "UserKey",
            fields = listOf(
                GraphQLInputValueDefinition("id", GraphQLTypeRef.Named("ID")),
                GraphQLInputValueDefinition("email", GraphQLTypeRef.Named("String")),
            ),
            isOneOf = true,
        ),
        GraphQLInterfaceType(
            name = "Node",
            fields = listOf(
                GraphQLFieldDefinition("id", GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("ID"))),
            ),
        ),
        GraphQLObjectType(
            name = "User",
            interfaces = setOf("Node"),
            fields = listOf(
                GraphQLFieldDefinition("id", GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("ID"))),
                GraphQLFieldDefinition("name", GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("String"))),
            ),
        ),
        GraphQLObjectType(
            name = "Product",
            interfaces = setOf("Node"),
            fields = listOf(
                GraphQLFieldDefinition("id", GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("ID"))),
                GraphQLFieldDefinition("title", GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("String"))),
            ),
        ),
        GraphQLUnionType("SearchResult", setOf("User", "Product")),
        GraphQLObjectType(
            name = "Query",
            fields = listOf(
                GraphQLFieldDefinition(
                    name = "node",
                    type = GraphQLTypeRef.Named("Node"),
                    arguments = listOf(
                        GraphQLInputValueDefinition(
                            "id",
                            GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("ID")),
                        ),
                    ),
                ),
                GraphQLFieldDefinition(
                    name = "search",
                    type = GraphQLTypeRef.NonNull(
                        GraphQLTypeRef.ListType(
                            GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("SearchResult")),
                        ),
                    ),
                    arguments = listOf(
                        GraphQLInputValueDefinition(
                            "term",
                            GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("String")),
                        ),
                        GraphQLInputValueDefinition("filter", GraphQLTypeRef.Named("Filter")),
                        GraphQLInputValueDefinition(
                            "limit",
                            GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("Int")),
                            OptionalInput.Present(JsonPrimitive(10)),
                        ),
                    ),
                ),
                GraphQLFieldDefinition(
                    name = "user",
                    type = GraphQLTypeRef.Named("User"),
                    arguments = listOf(
                        GraphQLInputValueDefinition(
                            "key",
                            GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("UserKey")),
                        ),
                    ),
                ),
            ),
        ),
        GraphQLObjectType(
            name = "Mutation",
            fields = listOf(
                GraphQLFieldDefinition(
                    "rename",
                    GraphQLTypeRef.Named("User"),
                    listOf(
                        GraphQLInputValueDefinition(
                            "name",
                            GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("String")),
                        ),
                    ),
                ),
            ),
        ),
    ),
)
