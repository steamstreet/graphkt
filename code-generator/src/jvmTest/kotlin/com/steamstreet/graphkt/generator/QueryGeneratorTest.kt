package com.steamstreet.graphkt.generator

import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.*
import kotlin.test.Test

/**
 * Tests for the query generator with focus on polymorphism support
 */
class QueryGeneratorTest {
    @Test
    fun testPolymorphism(@TempDir outputDir: File) {
        val parser = SchemaParser()
        val schema = parser.parse("""
            schema {
                query: Query
            }
            
            type Query {
                node(id: ID!): Node
                search(term: String): [SearchResult]
            }
            
            interface Node {
                id: ID!
                name: String!
            }
            
            interface SearchResult {
                score: Float!
            }
            
            type User implements Node {
                id: ID!
                name: String!
                email: String!
                profilePicture: String
            }
            
            type Product implements Node & SearchResult {
                id: ID!
                name: String!
                price: Float!
                description: String
                score: Float!
            }
            
            type Article implements Node & SearchResult {
                id: ID!
                name: String!
                content: String!
                author: User!
                score: Float!
            }
        """.trimIndent())
        
        val packageName = "com.steamstreet.testpolymorphism"
        
        // Generate the query code
        ServerMappingGenerator(schema, packageName, Properties(), outputDir).execute()
        
        // Print the generated files
        println("Generated Query Files:")
        outputDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList().forEach {
                println("\n--- ${it.name} ---")
                it.readText().also { text ->
                    println(text)
                }
            }
    }
}