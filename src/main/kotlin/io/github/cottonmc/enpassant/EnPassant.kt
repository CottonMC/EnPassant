package io.github.cottonmc.enpassant

import io.github.cottonmc.enpassant.task.EnPassantProguardTask
import io.github.cottonmc.enpassant.task.MergeJarWithDirectoryTask
import io.github.cottonmc.enpassant.task.RenameReferencesTask
import io.github.cottonmc.enpassant.util.JsonUtil
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.BasePluginConvention
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

        copyDefaultDictionaries(target)

        val proguardTask: EnPassantProguardTask = target.createTask("proguard") {
            val jar = target.tasks.getByName("jar")
            dependsOn(jar)
            injars(jar.outputs.files)
            outjars(project.buildDir.resolve("proguard/output.jar"))
            printmapping(project.buildDir.resolve("proguard/mappings.txt"))
            packageobfuscationdictionary(project.buildDir.resolve("proguard/defaultDictionaries/packages.txt"))
            classobfuscationdictionary(project.buildDir.resolve("proguard/defaultDictionaries/classes.txt"))

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
                    keep(
                        mapOf("allowobfuscation" to extension.obfuscateMixins),
                        """
                        class $pkg.** {
                            *;
                        }
                        """.trimIndent()
                    )

                    keepclassmembernames(
                        """
                        class $pkg.** {
                            @org.spongepowered.asm.mixin.Shadow *;
                            @org.spongepowered.asm.mixin.Unique *;
                        }
                        """.trimIndent()
                    )
                }

                // TODO: Limit the automatically kept amount
                val entrypoints = JsonUtil.getEntrypointValues(cache)
                for (entrypoint in entrypoints) {
                    val className = entrypoint.substringBefore("::")
                    val memberName = if ("::" in entrypoint) entrypoint.substringAfter("::") else null
                    val member = if (memberName != null) "* $memberName;\n    * $memberName(*);" else ""

                    keep(
                        mapOf("allowobfuscation" to !project.hasKotlin),
                        """
                        class $className {
                            $member
                        }
                        """.trimIndent()
                    )
                }
            }
        }

        // TODO: Why does this not map mixin @Shadows like the default remapJar task?
        @Suppress("UnstableApiUsage")
        val remapProguardJarTask: RemapJarTask = target.createTask("remapProguardJar") {
            dependsOn(proguardTask)
            input.set { proguardTask.outJarFiles[0] as File }
            archiveClassifier.set("proguard-dev")
            addNestedDependencies.set(true)
        }

        val renameObfuscatedReferencesTask: RenameReferencesTask = target.createTask("renameObfuscatedReferences") {
            dependsOn(remapProguardJarTask)
            project.afterEvaluate {
                if (!hasInput()) input = remapProguardJarTask.outputs.files.singleFile
                if (!hasMappings()) mappings = proguardTask.getConfiguration().printMapping
            }
            output = project.buildDir.resolve("proguard/renameObfuscatedReferences")
        }

        @Suppress("UnstableApiUsage")
        val renamedProguardJarTask: MergeJarWithDirectoryTask = target.createTask("renamedProguardJar") {
            dependsOn(remapProguardJarTask, renameObfuscatedReferencesTask)
            target.afterEvaluate {
                val archivesBaseName = project.convention.getPlugin(BasePluginConvention::class.java).archivesBaseName
                output = project.buildDir.resolve("libs").resolve("$archivesBaseName-${project.version}-proguard.jar")
                jar = remapProguardJarTask.outputs.files.singleFile
                directory = renameObfuscatedReferencesTask.output
            }
        }

        target.tasks.getByName("build").dependsOn(renamedProguardJarTask)
    }

    private fun copyDefaultDictionaries(project: Project) {
        val dir = project.buildDir.toPath().resolve("proguard").resolve("defaultDictionaries")
        Files.createDirectories(dir)
        Files.copy(
            EnPassant::class.java.getResourceAsStream("/en-passant/dictionaries/packages.txt"),
            dir.resolve("packages.txt"),
            StandardCopyOption.REPLACE_EXISTING
        )
        Files.copy(
            EnPassant::class.java.getResourceAsStream("/en-passant/dictionaries/classes.txt"),
            dir.resolve("classes.txt"),
            StandardCopyOption.REPLACE_EXISTING
        )
    }
}
