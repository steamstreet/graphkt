package com.steamstreet.graphkt.generator.schema

import com.squareup.kotlinpoet.ClassName
import graphql.schema.idl.SchemaParser
import kotlin.test.Test
import kotlin.test.assertEquals

class KotlinTypeMapperTest {
    private val model = SchemaNormalizer().normalize(
        SchemaParser().parse(
            """
            scalar Instant

            enum Status {
                ACTIVE
            }

            type Query {
                users: [[User!]!]!
            }

            type User {
                id: ID!
            }
            """.trimIndent(),
        ),
    )
    private val mapper = KotlinTypeMapper(model, "com.example")

    @Test
    fun `maps nested list nullability`() {
        val type = TypeRef.NonNull(
            TypeRef.ListType(
                TypeRef.NonNull(
                    TypeRef.ListType(
                        TypeRef.NonNull(TypeRef.Named("User")),
                    ),
                ),
            ),
        )

        assertEquals(
            "kotlin.collections.List<kotlin.collections.List<com.example.User>>",
            mapper.map(type).toString(),
        )
    }

    @Test
    fun `keeps enums and scalars in the base package`() {
        assertEquals(ClassName("com.example", "Status").copy(nullable = true), mapper.map(TypeRef.Named("Status")))
        assertEquals(ClassName("com.example", "Instant").copy(nullable = true), mapper.map(TypeRef.Named("Instant")))
    }

    @Test
    fun `applies a postfix only to object types`() {
        assertEquals(
            ClassName("com.example.client", "UserResponse").copy(nullable = true),
            mapper.map(TypeRef.Named("User"), postfix = "Response", overriddenPackage = "com.example.client"),
        )
        assertEquals(
            ClassName("com.example", "Status").copy(nullable = true),
            mapper.map(TypeRef.Named("Status"), postfix = "Response", overriddenPackage = "com.example.client"),
        )
    }
}
