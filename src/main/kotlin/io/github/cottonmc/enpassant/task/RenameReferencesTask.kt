package io.github.cottonmc.enpassant.task

import arrow.core.Either
import arrow.core.Left
import arrow.core.Right
import blue.endless.jankson.JsonGrammar
import io.github.cottonmc.enpassant.util.JsonCache
import io.github.cottonmc.enpassant.util.JsonUtil
import io.github.cottonmc.proguardparser.ClassMapping
import io.github.cottonmc.proguardparser.ProjectMapping
import io.github.cottonmc.proguardparser.Renameable
import io.github.cottonmc.proguardparser.parseProguardMappings
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

open class RenameReferencesTask : DefaultTask() {
    @get:InputFile
    lateinit var input: File

    @get:InputFile
    lateinit var mappings: File

    @get:OutputDirectory
    lateinit var output: File

    internal fun hasInput(): Boolean = this::input.isInitialized
    internal fun hasMappings(): Boolean = this::mappings.isInitialized

    @TaskAction
    open fun renameFiles() {
        if (output.exists()) {
            output.deleteRecursively()
        }
        output.mkdirs()

        val mappings = parseProguardMappings(this.mappings.readLines())

        val inputTree = project.zipTree(input)
        val cache = JsonCache { path -> inputTree.matching { it.include(path) }.singleFile.inputStream() }

        processModJson(mappings, cache, output)
        processMixins(mappings, cache, output)
    }

    private fun processModJson(mappings: ProjectMapping, cache: JsonCache, outputDirectory: File) {
        // TODO: This can work on the raw JSON object
        val lineMapper: (String) -> String =
            fun(line: String) = JsonUtil.getEntrypointValues(cache)
                .asSequence()
                .map { entrypoint ->
                    // Entrypoint classes (a.b.c.Mod)
                    // => Either.Left
                    val classes = mappings.classes.asSequence()
                        .filter { c -> entrypoint == c.from }
                        .map(::Left)

                    // Entrypoint members (a.b.c.Mod::init)
                    // => Either.Right
                    val members = mappings.classes.asSequence()
                        .flatMap { c ->
                            val methods = c.methods.asSequence().map { m -> c to m }
                            val fields = c.fields.asSequence().map { f -> c to f }
                            methods + fields
                        }
                        .filter { (c, r) -> entrypoint == "${c.from}::${r.from}" }
                        .map(::Right)

                    classes + members
                }
                .onEach { candidates ->
                    // Check for duplicated Either.Right members
                    val candidateList = candidates
                        .filterIsInstance<Either.Right<Pair<ClassMapping, Renameable>>>()
                        .map { it.b }
                        .toList()

                    if (candidateList.size > 1) {
                        val (clazz, candidate) = candidateList.first()
                        val name = "${clazz.from}::${candidate.from}"
                        project.logger.warn(":found more than one entrypoint candidate for '$name'")
                    }
                }
                .flatten() // Flatten groups of entrypoint candidates
                .fold(line) { acc, candidate ->
                    when (candidate) {
                        is Either.Left -> {
                            val value = candidate.a
                            acc.replace("\"${value.from}\"", "\"${value.to}\"")
                        }
                        is Either.Right -> {
                            val (clazz, member) = candidate.b
                            val from = "${clazz.from}::${member.from}"
                            val to = "${clazz.to}::${member.to}"
                            acc.replace("\"$from\"", "\"$to\"")
                        }
                    }
                }

        val input = cache["fabric.mod.json"].toJson(JsonGrammar.STRICT).lineSequence()
        val output = input.map(lineMapper).map { it + '\n' }.joinToString(separator = "")
        outputDirectory.resolve("fabric.mod.json").writeText(output)
    }

    // TODO: Process refmaps
    private fun processMixins(mappings: ProjectMapping, cache: JsonCache, outputDirectory: File) {
        // TODO: This can work on the raw JSON object
        val mixins = JsonUtil.getMixinJsonPaths(cache)
        for (mixin in mixins) {
            val input = cache[mixin].toJson(JsonGrammar.STRICT).lineSequence()
            val output = input.map {
                val oldPackage = JsonUtil.getMixinPackage(cache, mixin)
                val newPackage = mappings.findPackage(oldPackage) ?: oldPackage

                mappings.findClassesInPackage(oldPackage)
                    .fold(it.replace("\"$oldPackage\"", "\"$newPackage\"")) { acc, clazz ->
                        val from = clazz.from.substringAfter("$oldPackage.")
                        val to = clazz.to.substringAfter("$newPackage.")
                        acc.replace("\"$from\"", "\"$to\"")
                    }
            }.map { it + '\n' }.joinToString(separator = "")
            val outputFile = outputDirectory.resolve(mixin)
            outputFile.parentFile.mkdirs()
            outputFile.writeText(output)
        }
    }
}
