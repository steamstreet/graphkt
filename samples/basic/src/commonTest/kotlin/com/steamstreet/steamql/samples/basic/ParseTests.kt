package com.steamstreet.steamql.samples.basic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import org.amshove.kluent.shouldBeEqualTo
import kotlin.test.Test
import kotlin.test.fail

class ParseTests {
    private fun parse(str: String): QueryResponseData {
        return QueryResponseData(Json(JsonConfiguration.Stable).parseJson(str).jsonObject)
    }

    @Test
    fun testBasics() {
        //language=JSON
        val response = parse("""
{
  "aStr": "Some String",
  "aBool": false
}
""")
        response.aStr shouldBeEqualTo "Some String"
        response.aBool shouldBeEqualTo false

        try {
            response.aNonNull
            fail("since the value isn't in the JSON, an exception should be thrown here.")
        } catch (npe: NullPointerException) {
        }
    }
}