package io.github.cottonmc.enpassant.task

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files

open class MergeJarWithDirectoryTask : DefaultTask() {
    @get:InputFile
    lateinit var jar: File

    @get:InputDirectory
    lateinit var directory: File

    @get:OutputFile
    lateinit var output: File

    @TaskAction
    fun run() {
        project.logger.info(":merging $jar and $directory into $output")
        output.delete()
        val env = hashMapOf("create" to "true")
        val outputUri = URI.create("jar:${output.toURI()}")
        FileSystems.newFileSystem(outputUri, env).use { fs ->
            project.zipTree(jar).visit {
                val segments = it.relativePath.segments
                val path = fs.getPath(segments.first(), *segments.drop(1).toTypedArray())

                if (!it.isDirectory) {
                    path.parent?.let { parent -> Files.createDirectories(parent) }
                    Files.write(path, it.file.readBytes())
                }
            }

            project.fileTree(directory).visit {
                val segments = it.relativePath.segments
                val path = fs.getPath(segments.first(), *segments.drop(1).toTypedArray())

                if (!it.isDirectory) {
                    path.parent?.let { parent -> Files.createDirectories(parent) }
                    Files.deleteIfExists(path)
                    Files.write(path, it.file.readBytes())
                }
            }
        }
    }
}
