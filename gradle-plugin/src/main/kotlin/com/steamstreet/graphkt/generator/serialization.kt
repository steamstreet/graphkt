package com.steamstreet.graphkt.generator

import com.squareup.kotlinpoet.ClassName

val jsonArrayFunction = ClassName("kotlinx.serialization.json", "jsonArray")
val jsonObjectFunction = ClassName("kotlinx.serialization.json", "jsonObject")
val jsonPrimitiveFunction = ClassName("kotlinx.serialization.json", "jsonPrimitive")

val jsonArrayType = ClassName("kotlinx.serialization.json", "JsonArray")
val jsonElementType = ClassName("kotlinx.serialization.json", "JsonElement")
val jsonObjectType = ClassName("kotlinx.serialization.json", "JsonObject")
val jsonNullType = ClassName("kotlinx.serialization.json", "JsonNull")
val jsonPrimitiveType = ClassName("kotlinx.serialization.json", "JsonPrimitive")

val serializerFunction = ClassName("kotlinx.serialization.builtins", "serializer")
