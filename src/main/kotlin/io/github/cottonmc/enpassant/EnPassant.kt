package io.github.cottonmc.enpassant

import io.github.cottonmc.enpassant.util.JsonCache
import io.github.cottonmc.enpassant.util.JsonUtil
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.jvm.tasks.Jar
import proguard.gradle.ProGuardTask

internal val Project.hasKotlin
    get() = plugins.hasPlugin("org.jetbrains.kotlin.jvm")

internal val Project.enPassant: EnPassantExtension
    get() = extensions.getByName("enPassant") as EnPassantExtension

class EnPassant : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create("enPassant", EnPassantExtension::class.java)

        target.tasks.create("proguard", ProGuardTask::class.java) {
            with(it) {
                val jar = target.tasks.getByName("jar") as Jar
                it.dependsOn(jar)
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

                    val extension = project.enPassant
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
                    val cache = JsonCache { path ->
                        (project.enPassant.buildResourceRoot ?: project.buildDir.resolve("resources/main"))
                            .resolve(path).inputStream()
                    }
                    val mixinPackages = JsonUtil.getMixinJsonPaths(cache).map { mixinJson ->
                        JsonUtil.getMixinPackage(cache, mixinJson)
                    }
                    for (pkg in mixinPackages) {
                        keep(mapOf("allowobfuscation" to true), "class $pkg.** {\n    *;\n}")
                    }

                    // TODO: Keep the necessary entrypoint stuff here
                }
            }
        }
    }
}