package com.steamstreet.graphkt.generator

import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.*
import kotlin.test.Test

/**
 * Tests of the data types generator
 */
class DataTypesGeneratorTest {
    @Test
    fun testWithEnums(@TempDir outputDir: File) {
        val parser = SchemaParser()
        val schema = parser.parse("""
            schema {
                query: Query
            }

            type Query {
                products(category: ProductCategory): [Product]
                orders(status: OrderStatus!): [Order]
            }

            enum ProductCategory {
                ELECTRONICS
                CLOTHING
                BOOKS
                FOOD
                OTHER
            }

            enum OrderStatus {
                PENDING
                PROCESSING
                SHIPPED
                DELIVERED
                CANCELLED
            }

            type Product {
                id: ID!
                name: String!
                category: ProductCategory!
                price: Float!
            }

            type Order {
                id: ID!
                products: [Product!]!
                status: OrderStatus!
                createdAt: String!
            }
        """.trimIndent())

        val packageName = "com.steamstreet.testenums"

        Generator(schema, packageName, Properties(), outputDir).generate(server = false)
//        DataTypesGenerator(schema, packageName, Properties(), outputDir).execute()

        outputDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList().forEach {
                it.readText().also { text ->
                    println(text)
                }
            }
//        validateCompilation(outputDir)
    }
}
