package generator

import com.steamstreet.graphkt.generator.ServerInterfacesGenerator
import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.*
import kotlin.test.Test

class ServerInterfacesGeneratorTest {
    @Test
    fun basics(@TempDir outputDir: File) {
        val parser = SchemaParser()
        val schema = parser.parse("""
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
            }
            
            type Another {
                value: String
            }
        """.trimIndent())

        val packageName = "com.steamstreet.teststeam"

        ServerInterfacesGenerator(schema, packageName, Properties(), outputDir).execute()

        val interfaces = File(outputDir, "com/steamstreet/teststeam/server/services.kt").readText()
        println(interfaces)
    }
}