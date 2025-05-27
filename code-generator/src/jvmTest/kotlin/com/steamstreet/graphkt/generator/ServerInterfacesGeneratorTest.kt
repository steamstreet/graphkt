package com.steamstreet.graphkt.generator

import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Properties
import kotlin.test.Test

class ServerInterfacesGeneratorTest {
    @Test
    fun basics(@TempDir outputDir: File) {
        testCompilation("""
            schema {
                query: Query
            }

            type Query {
                name: String
                cities: String!
                isHuman: Boolean
                age: Int!

                another: Another
                anotherOne: Another!

                alist: [String]
                aNonNullList: [String]!

                withParam(name: String!): String!
            }

            type Another {
                value: String
            }
        """.trimIndent(), outputDir)
    }

    @Test
    fun testWithInterfaces(@TempDir outputDir: File) {
        testCompilation("""
            schema {
                query: Query
            }

            interface Node {
                id: ID!
            }

            interface Entity {
                name: String
                description: String
            }

            type Query {
                node(id: ID!): Node
                entities: [Entity]
            }

            type User implements Node & Entity {
                id: ID!
                name: String
                description: String
                email: String
                age: Int
            }

            type Product implements Node & Entity {
                id: ID!
                name: String
                description: String
                price: Float
                inStock: Boolean
            }
        """.trimIndent(), outputDir)
    }

    fun testCompilation(schema: String, outputDir: File) {
        val parser = SchemaParser()
        val schema = parser.parse(schema)
        val packageName = "com.steamstreet.testinputs"
        Generator(schema, packageName, Properties(), outputDir).generate(server = false)
//        validateCompilation(outputDir)
    }

    @Test
    fun testWithInputTypes(@TempDir outputDir: File) {
        testCompilation("""
            schema {
                query: Query
                mutation: Mutation
            }

            type Query {
                users: [User]
                user(id: ID!): User
            }

            type Mutation {
                createUser(input: UserInput!): User
                updateUser(id: ID!, input: UserInput!): User
            }

            type User {
                id: ID!
                name: String!
                email: String!
                address: Address
            }

            type Address {
                street: String
                city: String
                state: String
                zipCode: String
            }

            input UserInput {
                name: String!
                email: String!
                address: AddressInput
            }

            input AddressInput {
                street: String
                city: String
                state: String
                zipCode: String
            }
        """.trimIndent(), outputDir)
    }



    @Test
    fun testWithUnions(@TempDir outputDir: File) {
        testCompilation("""
            schema {
                query: Query
            }

            type Query {
                search(term: String!): [SearchResult]
                feed: [FeedItem]
            }

            union SearchResult = User | Post | Comment

            union FeedItem = Post | Comment | Advertisement

            type User {
                id: ID!
                username: String!
                email: String!
            }

            type Post {
                id: ID!
                title: String!
                content: String!
                author: User!
            }

            type Comment {
                id: ID!
                content: String!
                author: User!
                post: Post!
            }

            type Advertisement {
                id: ID!
                title: String!
                imageUrl: String!
                targetUrl: String!
            }
        """.trimIndent(), outputDir)
    }

    @Test
    fun testWithDirectivesAndComplexTypes(@TempDir outputDir: File) {
        testCompilation("""
            schema {
                query: Query
                mutation: Mutation
                subscription: Subscription
            }

            directive @deprecated(
                reason: String = "No longer supported"
            ) on FIELD_DEFINITION | ENUM_VALUE

            directive @auth(
                requires: Role = ADMIN
            ) on FIELD_DEFINITION

            enum Role {
                ADMIN
                USER
                GUEST
            }

            type Query {
                users: [User] @auth(requires: ADMIN)
                me: User
                posts(filter: PostFilter): [Post]
                postById(id: ID!): Post
            }

            type Mutation {
                createPost(input: PostInput!): Post
                updatePost(id: ID!, input: PostInput!): Post
                deletePost(id: ID!): Boolean
            }

            type Subscription {
                postAdded: Post
                postUpdated(id: ID!): Post
            }

            type User {
                id: ID!
                name: String!
                email: String!
                role: Role!
                posts: [Post]
                profile: Profile
            }

            type Profile {
                bio: String
                avatar: String
                socialLinks: [SocialLink]
            }

            type SocialLink {
                platform: String!
                url: String!
            }

            type Post {
                id: ID!
                title: String!
                content: String!
                author: User!
                tags: [String]
                createdAt: String!
                updatedAt: String
                status: PostStatus!
                viewCount: Int @deprecated(reason: "Use analytics API instead")
            }

            enum PostStatus {
                DRAFT
                PUBLISHED
                ARCHIVED
            }

            input PostInput {
                title: String!
                content: String!
                tags: [String]
                status: PostStatus = DRAFT
            }

            input PostFilter {
                authorId: ID
                status: PostStatus
                tags: [String]
                searchTerm: String
            }
        """.trimIndent(), outputDir)
    }
}