package io.github.cottonmc.enpassant

import arrow.core.Either
import arrow.core.Left
import arrow.core.Right
import io.github.cottonmc.enpassant.task.EnPassantProguardTask
import io.github.cottonmc.enpassant.util.JsonUtil
import io.github.cottonmc.proguardparser.ClassMapping
import io.github.cottonmc.proguardparser.Renameable
import io.github.cottonmc.proguardparser.parseProguardMappings
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.jvm.tasks.Jar
import java.io.File

internal val Project.hasKotlin
    get() = plugins.hasPlugin("org.jetbrains.kotlin.jvm")

internal val Project.enPassant: EnPassantExtension
    get() = extensions.getByName("enPassant") as EnPassantExtension

private inline fun <reified T : Task> Project.createTask(name: String, crossinline fn: T.() -> Unit): T =
    tasks.create(name, T::class.java) { it.fn() }

class EnPassant : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create("enPassant", EnPassantExtension::class.java)
        extension.project = target

        val proguardTask: EnPassantProguardTask = target.createTask("proguard") {
            val jar = target.tasks.getByName("jar") as Jar
            dependsOn(jar)
            injars(jar.outputs.files)
            outjars(project.buildDir.resolve("proguard/output.jar"))
            printmapping(project.buildDir.resolve("proguard/mappings.txt"))

            // Keep @Environment and mixin annotations.
            // This also keeps @kotlin.Metadata :/
            keepattributes("RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations")

            project.afterEvaluate {
                if (project.hasKotlin) {
                    assumenosideeffects(
                        """
                        class kotlin.jvm.internal.Intrinsics {
                            static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
                            static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
                        }
                        """.trimIndent()
                    )
                }

                extension.rootPackage?.let { root -> flattenpackagehierarchy(root) }
                extension.packageDictionary?.let { dict -> packageobfuscationdictionary(dict) }
                extension.classDictionary?.let { dict -> classobfuscationdictionary(dict) }
                extension.memberDictionary?.let { dict -> obfuscationdictionary(dict) }
                extension.mappingsFile?.let { file -> printmapping(file) }

                // TODO: Does this even include the JDK?
                libraryjars(project.configurations.getByName("compileClasspath").files)
                libraryjars(project.configurations.getByName("runtimeClasspath").files)
            }

            doFirst {
                val cache = extension.jsonCache
                val mixinPackages = JsonUtil.getMixinJsonPaths(cache).map { mixinJson ->
                    JsonUtil.getMixinPackage(cache, mixinJson)
                }
                for (pkg in mixinPackages) {
                    keep(mapOf("allowobfuscation" to true), "class $pkg.** {\n    *;\n}")
                }

                // TODO: Limit the automatically kept amount
                val entrypoints = JsonUtil.getEntrypointValues(cache).map { it.substringBeforeLast("::") }
                for (entrypoint in entrypoints) {
                    keep(
                        """
                        class $entrypoint {
                            *;
                        }
                        """.trimIndent()
                    )
                }
            }
        }

        @Suppress("UnstableApiUsage")
        val remapProguardJarTask: RemapJarTask = target.createTask("remapProguardJar") {
            dependsOn(proguardTask)
            input.set { proguardTask.outJarFiles[0] as File }
            archiveClassifier.set("proguard-dev")
            addNestedDependencies.set(true)
        }

        @Suppress("UnstableApiUsage")
        val renamedProguardJarTask: Jar = target.createTask("renamedProguardJar") {
            dependsOn(remapProguardJarTask)
            archiveClassifier.set("proguard")

            project.afterEvaluate {
                val cache = extension.jsonCache
                val mixins = JsonUtil.getMixinJsonPaths(cache)
                val mappings = parseProguardMappings(proguardTask.getConfiguration().printMapping.readLines())

                for (output in remapProguardJarTask.outputs.files) {
                    from(project.zipTree(output))
                        .exclude("fabric.mod.json")
                        .exclude(mixins)

                    from(project.zipTree(output)) {
                        include("fabric.mod.json")
                        filter { line ->
                            JsonUtil.getEntrypointValues(cache)
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
                        }
                    }

                    for (mixin in mixins) {
                        from(project.zipTree(output)) {
                            include(mixin)
                            filter {
                                val oldPackage = JsonUtil.getMixinPackage(cache, mixin)
                                val newPackage = mappings.findPackage(oldPackage) ?: oldPackage

                                mappings.findClassesInPackage(oldPackage)
                                    .fold(it.replace(oldPackage, newPackage)) { acc, clazz ->
                                        val from = clazz.from.substringAfter(oldPackage)
                                        val to = clazz.to.substringAfter(newPackage)
                                        acc.replace("\"$from\"", "\"$to\"")
                                    }
                            }
                        }
                    }
                }
            }
        }

        target.tasks.getByName("build").dependsOn(renamedProguardJarTask)
    }
}
