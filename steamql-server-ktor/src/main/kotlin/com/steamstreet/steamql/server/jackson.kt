package com.steamstreet.steamql.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

val graphQLJackson: ObjectMapper = jacksonObjectMapper()

inline fun <reified T> ObjectMapper.convert(value: Any): T = this.convertValue(value, T::class.java)