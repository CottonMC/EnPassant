package io.github.cottonmc.enpassant

import arrow.core.Either
import arrow.core.Left
import arrow.core.Right
import io.github.cottonmc.enpassant.task.EnPassantProguardTask
import io.github.cottonmc.enpassant.task.RenameReferencesTask
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

                val classpaths = HashSet<File>() // Exclude duplicates
                classpaths += project.configurations.getByName("compileClasspath").files
                classpaths += project.configurations.getByName("runtimeClasspath").files
                libraryjars(classpaths)
                libraryjars("${System.getProperty("java.home")}/lib/rt.jar") // This only works on JDK <=8
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

        val renameObfuscatedReferencesTask: RenameReferencesTask = target.createTask("renameObfuscatedReferences") {
            dependsOn(remapProguardJarTask)
            input = remapProguardJarTask.outputs.files.singleFile
            mappings = proguardTask.getConfiguration().printMapping
            output = project.buildDir.resolve("proguard/renameObfuscatedReferences")
        }

        @Suppress("UnstableApiUsage")
        val renamedProguardJarTask: Jar = target.createTask("renamedProguardJar") {
            dependsOn(remapProguardJarTask, renameObfuscatedReferencesTask)
            archiveClassifier.set("proguard")
            target.afterEvaluate {
                val output = remapProguardJarTask.outputs.files.singleFile
                from(project.zipTree(output)) {
                    exclude("fabric.mod.json")
                    exclude { it.relativePath.getFile(renameObfuscatedReferencesTask.output).exists() }
                }
                from(project.fileTree(renameObfuscatedReferencesTask.output))
            }
        }

        target.tasks.getByName("build").dependsOn(renamedProguardJarTask)
    }
}
