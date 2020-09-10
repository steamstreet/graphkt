package com.steamstreet.graphkt.samples.server

import com.steamstreet.graphkt.server.ktor.graphQL
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main() {
    val server = embeddedServer(Netty, port = 8081) {
        routing {
            get("/") {
                call.respondText("Hello World!", ContentType.Text.Plain)
            }
            route("/graphql") {
                graphQL {
                    query { _, it ->
                        val result = Server().gqlSelect(it)
                        result
                    }
                }
            }
        }
    }
    server.start(wait = true)
}

class Server : Query {
    override suspend fun person(id: String): Person? {
        println(id)
        return PersonImpl()
    }
}

class PersonImpl : Person {
    override val name: String?
        get() = "Robin"

    override val friends: List<String>
        get() = listOf("Jonathan", "Bill", "Reilly")

    override val phoneNumber: PhoneNumber?
        get() = TODO("not implemented")
}