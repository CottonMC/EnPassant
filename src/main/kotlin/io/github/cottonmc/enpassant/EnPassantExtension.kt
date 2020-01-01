package io.github.cottonmc.enpassant

import io.github.cottonmc.enpassant.util.JsonCache
import org.gradle.api.Project
import java.io.File

open class EnPassantExtension {
    var rootPackage: String? = null

    var packageDictionary: File? = null
    var classDictionary: File? = null
    var memberDictionary: File? = null

    var buildResourceRoot: File? = null

    var mappingsFile: File? = null

    var obfuscateMixins: Boolean = false

    internal lateinit var project: Project

    internal val jsonCache: JsonCache by lazy {
        JsonCache { path ->
            (project.enPassant.buildResourceRoot ?: project.buildDir.resolve("resources/main"))
                .resolve(path).inputStream()
        }
    }
}
