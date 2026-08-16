package com.steamstreet.graphkt.generator.schema

import graphql.schema.idl.SchemaParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SchemaNormalizerTest {
    private val parser = SchemaParser()
    private val normalizer = SchemaNormalizer()

    @Test
    fun `normalizes explicit operation roots and union members`() {
        val schema = parser.parse(
            """
            schema {
                query: RootQuery
                mutation: RootMutation
            }

            type RootQuery {
                search: SearchResult
            }

            type RootMutation {
                refresh: Boolean!
            }

            union SearchResult = Product | User

            type Product {
                id: ID!
            }

            type User {
                id: ID!
            }
            """.trimIndent(),
        )

        val model = normalizer.normalize(schema)

        assertEquals(
            listOf(
                OperationRoot(OperationKind.QUERY, "RootQuery"),
                OperationRoot(OperationKind.MUTATION, "RootMutation"),
            ),
            model.operations,
        )
        assertEquals(listOf("Product", "User"), model.type<UnionType>("SearchResult")?.members)
    }

    @Test
    fun `infers conventional roots when the schema definition is absent`() {
        val schema = parser.parse(
            """
            type Query {
                version: String!
            }
            """.trimIndent(),
        )

        val model = normalizer.normalize(schema)

        assertEquals(listOf(OperationRoot(OperationKind.QUERY, "Query")), model.operations)
        assertNotNull(model.type<ScalarType>("String"))
    }

    @Test
    fun `preserves nested list nullability`() {
        val schema = parser.parse(
            """
            type Query {
                matrix: [[String!]!]!
            }
            """.trimIndent(),
        )

        val model = normalizer.normalize(schema)
        val query = assertNotNull(model.type<ObjectType>("Query"))
        val matrix = assertNotNull(query.fields.singleOrNull { it.name == "matrix" })

        assertEquals(
            TypeRef.NonNull(
                TypeRef.ListType(
                    TypeRef.NonNull(
                        TypeRef.ListType(
                            TypeRef.NonNull(TypeRef.Named("String")),
                        ),
                    ),
                ),
            ),
            matrix.type,
        )
    }

    @Test
    fun `preserves input default values`() {
        val schema = parser.parse(
            """
            enum Status {
                DRAFT
                PUBLISHED
            }

            input Filter {
                limit: Int = 25
                statuses: [Status!] = [DRAFT, PUBLISHED]
            }

            type Query {
                posts(filter: Filter = { limit: 10 }): [String!]!
            }
            """.trimIndent(),
        )

        val model = normalizer.normalize(schema)
        val filter = assertNotNull(model.type<InputObjectType>("Filter"))
        val statuses = assertNotNull(filter.fields.singleOrNull { it.name == "statuses" })
        val listDefault = assertIs<ConstantValue.ListValue>(statuses.defaultValue)

        assertEquals(
            listOf(
                ConstantValue.EnumValue("DRAFT"),
                ConstantValue.EnumValue("PUBLISHED"),
            ),
            listDefault.values,
        )

        val query = assertNotNull(model.type<ObjectType>("Query"))
        val posts = assertNotNull(query.fields.singleOrNull { it.name == "posts" })
        val filterDefault = assertIs<ConstantValue.ObjectValue>(posts.arguments.single().defaultValue)

        assertEquals(
            listOf(ObjectField("limit", ConstantValue.IntValue("10"))),
            filterDefault.fields,
        )
    }

    @Test
    fun `preserves custom directive definitions`() {
        val schema = parser.parse(
            """
            directive @cached(ttl: Int! = 60) repeatable on FIELD | QUERY

            type Query {
                version: String!
            }
            """.trimIndent(),
        )

        val model = normalizer.normalize(schema)
        val directive = assertNotNull(model.directives.singleOrNull { it.name == "cached" })

        assertEquals(true, directive.repeatable)
        assertEquals(setOf(DirectiveLocation.FIELD, DirectiveLocation.QUERY), directive.locations)
        assertEquals(ConstantValue.IntValue("60"), directive.arguments.single().defaultValue)
    }
}
