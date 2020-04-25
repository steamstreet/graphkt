package com.steamstreet.steamql.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration

val steamQlJson = Json(JsonConfiguration.Stable.copy(isLenient = true, ignoreUnknownKeys = true, useArrayPolymorphism = true))