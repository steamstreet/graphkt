package com.steamstreet.graphkt.generator

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

public const val GRAPHKT_EXTENSION_NAME: String = "graphKt"

@Deprecated("Use GRAPHKT_EXTENSION_NAME")
public const val EXTENSION_NAME: String = "GraphQL"

/** Enables one generated API family. */
public abstract class GraphKtFeature @Inject constructor(objects: ObjectFactory) {
    public val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
}

/** Lazy configuration for GraphKt source generation. */
public abstract class GraphQLExtension @Inject constructor(objects: ObjectFactory) {
    private var legacySchemaPath: String? = null

    public val schemaFiles: ConfigurableFileCollection = objects.fileCollection()
    public val propertiesFile: RegularFileProperty = objects.fileProperty()
    public val packageName: Property<String> = objects.property(String::class.java)
    public val client: GraphKtFeature = objects.newInstance(GraphKtFeature::class.java)
    public val server: GraphKtFeature = objects.newInstance(GraphKtFeature::class.java)

    public fun client(action: Action<GraphKtFeature>) {
        action.execute(client)
    }

    public fun server(action: Action<GraphKtFeature>) {
        action.execute(server)
    }

    @Deprecated("Use schemaFiles.from(...)")
    public var schema: String
        get() = legacySchemaPath ?: schemaFiles.files.sortedBy { it.path }.firstOrNull()?.path.orEmpty()
        set(value) {
            legacySchemaPath = value
            schemaFiles.setFrom(value)
            val resolvedSchema = schemaFiles.files.singleOrNull()
            val siblingSchemas = resolvedSchema?.parentFile?.listFiles { _, name ->
                name != resolvedSchema.name && name.endsWith(".graphql")
            }.orEmpty()
            schemaFiles.setFrom(listOfNotNull(resolvedSchema) + siblingSchemas)
            val companionProperties = resolvedSchema?.let {
                it.resolveSibling("${it.nameWithoutExtension}.properties")
            }
            propertiesFile.unset()
            if (companionProperties?.isFile == true) {
                propertiesFile.fileValue(companionProperties)
            }
        }

    @Deprecated("Use packageName.set(...)")
    public var basePackage: String
        get() = packageName.orNull.orEmpty()
        set(value) {
            packageName.set(value)
        }

    @Deprecated("Use client.enabled.set(...)")
    public var generateClient: Boolean
        get() = client.enabled.get()
        set(value) {
            client.enabled.set(value)
        }

    @Deprecated("Use server.enabled.set(...)")
    public var generateServer: Boolean
        get() = server.enabled.get()
        set(value) {
            server.enabled.set(value)
        }
}

internal fun Project.graphQL(): GraphQLExtension = extensions.getByType(GraphQLExtension::class.java)
