package com.steamstreet.graphkt.generator

import com.steamstreet.graphkt.generator.schema.SchemaNormalizer
import graphql.parser.InvalidSyntaxException
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import graphql.schema.idl.errors.SchemaProblem
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.Properties

public data class GenerationFeatures(
    val client: Boolean = true,
    val server: Boolean = true,
)

public data class GenerationOutputs(
    val common: File,
    val client: File = common,
    val server: File = common,
)

public data class GenerationRequest(
    val schemaFiles: List<File>,
    val propertiesFile: File? = null,
    val packageName: String,
    val features: GenerationFeatures = GenerationFeatures(),
    val outputs: GenerationOutputs,
)

public data class GenerationResult(
    val generatedFiles: List<File>,
    val operationCount: Int,
    val typeCount: Int,
    val diagnostics: List<GenerationDiagnostic> = emptyList(),
)

public enum class GenerationDiagnosticSeverity {
    WARNING,
    ERROR,
}

public data class GenerationSourceLocation(
    val file: File? = null,
    val line: Int? = null,
    val column: Int? = null,
)

public data class GenerationDiagnostic(
    val code: String,
    val severity: GenerationDiagnosticSeverity,
    val message: String,
    val location: GenerationSourceLocation? = null,
)

public class GenerationException(
    public val diagnostics: List<GenerationDiagnostic>,
    cause: Throwable? = null,
) : IllegalArgumentException(diagnostics.joinToString("\n", transform = GenerationDiagnostic::render), cause)

/** The single entry point for GraphKt source generation. */
public class GraphKtGenerator {
    public fun generate(request: GenerationRequest): GenerationResult {
        validate(request)

        val schemaFiles = request.schemaFiles
            .map { it.canonicalFile }
            .distinct()
            .sortedBy { it.path }

        val schema = parseSchema(schemaFiles)
        val model = try {
            SchemaNormalizer().normalize(schema)
        } catch (exception: Exception) {
            throw generationFailure(
                code = "GRAPHKT_SCHEMA_MODEL",
                message = exception.message ?: "The schema could not be normalized",
                cause = exception,
            )
        }
        val properties = loadProperties(request.propertiesFile)

        generateWithStagedOutputs(request) { stagedOutputs ->
            generateSources(
                schemaModel = model,
                packageName = request.packageName,
                properties = properties,
                features = request.features,
                outputs = stagedOutputs,
            )
        }

        return GenerationResult(
            generatedFiles = request.generatedFiles(),
            operationCount = model.operations.size,
            typeCount = model.types.size,
        )
    }

    private fun validate(request: GenerationRequest) {
        val diagnostics = buildList {
            if (request.schemaFiles.isEmpty()) {
                add(error("GRAPHKT_REQUEST", "At least one schema file is required"))
            }
            if (request.packageName.isBlank()) {
                add(error("GRAPHKT_REQUEST", "A package name is required"))
            }
            request.schemaFiles.filterNot(File::isFile).forEach { file ->
                add(
                    error(
                        code = "GRAPHKT_SCHEMA_FILE",
                        message = "Schema file does not exist",
                        location = GenerationSourceLocation(file = file.absoluteFile),
                    ),
                )
            }
            request.propertiesFile?.takeUnless(File::isFile)?.let { file ->
                add(
                    error(
                        code = "GRAPHKT_PROPERTIES_FILE",
                        message = "Properties file does not exist",
                        location = GenerationSourceLocation(file = file.absoluteFile),
                    ),
                )
            }
            activeOutputRoots(request).forEach { parent ->
                activeOutputRoots(request).forEach { child ->
                    if (parent != child && child.toPath().startsWith(parent.toPath())) {
                        add(
                            error(
                                code = "GRAPHKT_OUTPUT_LAYOUT",
                                message = "Generated output directories cannot contain one another: " +
                                    "${parent.path} and ${child.path}",
                            ),
                        )
                    }
                }
            }
        }.distinct()

        if (diagnostics.isNotEmpty()) throw GenerationException(diagnostics)
    }

    private fun parseSchema(schemaFiles: List<File>): TypeDefinitionRegistry {
        val parser = SchemaParser()
        val schema = TypeDefinitionRegistry()

        schemaFiles.forEach { schemaFile ->
            val parsed = try {
                parser.parse(schemaFile)
            } catch (exception: InvalidSyntaxException) {
                throw GenerationException(
                    diagnostics = listOf(
                        error(
                            code = "GRAPHKT_SCHEMA_SYNTAX",
                            message = exception.message ?: "The schema contains invalid syntax",
                            location = GenerationSourceLocation(
                                file = schemaFile,
                                line = exception.location?.line,
                                column = exception.location?.column,
                            ),
                        ),
                    ),
                    cause = exception,
                )
            } catch (exception: SchemaProblem) {
                throw GenerationException(
                    diagnostics = exception.errors.map { schemaError ->
                        val location = schemaError.locations?.firstOrNull()
                        error(
                            code = "GRAPHKT_SCHEMA_SYNTAX",
                            message = schemaError.message,
                            location = GenerationSourceLocation(
                                file = schemaFile,
                                line = location?.line,
                                column = location?.column,
                            ),
                        )
                    }.ifEmpty {
                        listOf(
                            error(
                                code = "GRAPHKT_SCHEMA_SYNTAX",
                                message = exception.message ?: "The schema contains invalid syntax",
                                location = GenerationSourceLocation(file = schemaFile),
                            ),
                        )
                    },
                    cause = exception,
                )
            }

            try {
                schema.merge(parsed)
            } catch (exception: Exception) {
                throw generationFailure(
                    code = "GRAPHKT_SCHEMA_MERGE",
                    message = exception.message ?: "The schema files could not be merged",
                    cause = exception,
                    location = GenerationSourceLocation(file = schemaFile),
                )
            }
        }

        return schema
    }

    private fun loadProperties(propertiesFile: File?): Properties = Properties().also { properties ->
        propertiesFile?.inputStream()?.use(properties::load)
    }

    private fun generateWithStagedOutputs(
        request: GenerationRequest,
        generate: (GenerationOutputs) -> Unit,
    ) {
        val targets = activeOutputRoots(request)
        val stages = targets.associateWith(::createStageDirectory)
        val stagedOutputs = GenerationOutputs(
            common = stages.getValue(request.outputs.common.canonicalFile),
            client = stages[request.outputs.client.canonicalFile] ?: request.outputs.client,
            server = stages[request.outputs.server.canonicalFile] ?: request.outputs.server,
        )

        try {
            generate(stagedOutputs)
            replaceOutputDirectories(stages)
        } catch (exception: GenerationException) {
            throw exception
        } catch (exception: Exception) {
            throw generationFailure(
                code = "GRAPHKT_GENERATION",
                message = exception.message ?: "Source generation failed",
                cause = exception,
            )
        } finally {
            stages.values.forEach { stage ->
                if (stage.exists()) stage.deleteRecursively()
            }
        }
    }

    private fun createStageDirectory(target: File): File {
        val parent = target.parentFile
        if (!parent.exists() && !parent.mkdirs()) {
            throw generationFailure(
                code = "GRAPHKT_OUTPUT",
                message = "Could not create the output parent directory: ${parent.path}",
            )
        }
        return Files.createTempDirectory(parent.toPath(), ".graphkt-stage-").toFile()
    }

    private fun replaceOutputDirectories(stages: Map<File, File>) {
        val backups = linkedMapOf<File, File>()
        val installedTargets = mutableListOf<File>()

        try {
            stages.keys.forEach { target ->
                if (target.exists()) {
                    val backup = Files.createTempDirectory(target.parentFile.toPath(), ".graphkt-backup-").toFile()
                    check(backup.delete()) { "Could not prepare output backup: ${backup.path}" }
                    moveDirectory(target, backup)
                    backups[target] = backup
                }
            }

            stages.forEach { (target, stage) ->
                moveDirectory(stage, target)
                installedTargets += target
            }
        } catch (exception: Exception) {
            installedTargets.asReversed().forEach { target ->
                if (target.exists()) target.deleteRecursively()
            }
            backups.entries.toList().asReversed().forEach { (target, backup) ->
                if (backup.exists() && !target.exists()) moveDirectory(backup, target)
            }
            throw exception
        } finally {
            backups.values.forEach { backup ->
                if (backup.exists()) backup.deleteRecursively()
            }
        }
    }

    private fun moveDirectory(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun GenerationRequest.generatedFiles(): List<File> = buildList {
        add(outputs.common)
        if (features.client) add(outputs.client)
        if (features.server) add(outputs.server)
    }.map { it.canonicalFile }
        .distinct()
        .flatMap { output ->
            output.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        .sortedBy { it.path }
}

private fun activeOutputRoots(request: GenerationRequest): List<File> = buildList {
    add(request.outputs.common)
    add(request.outputs.client)
    add(request.outputs.server)
}.map(File::getCanonicalFile).distinct()

private fun error(
    code: String,
    message: String,
    location: GenerationSourceLocation? = null,
): GenerationDiagnostic = GenerationDiagnostic(
    code = code,
    severity = GenerationDiagnosticSeverity.ERROR,
    message = message,
    location = location,
)

private fun generationFailure(
    code: String,
    message: String,
    cause: Throwable? = null,
    location: GenerationSourceLocation? = null,
): GenerationException = GenerationException(listOf(error(code, message, location)), cause)

private fun GenerationDiagnostic.render(): String = buildString {
    append(code)
    append(": ")
    location?.file?.let { append(it.path).append(": ") }
    location?.line?.let { line ->
        append(line)
        location.column?.let { column -> append(':').append(column) }
        append(": ")
    }
    append(message)
}
