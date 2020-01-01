package io.github.cottonmc.enpassant.task

import proguard.Configuration
import proguard.gradle.ProGuardTask

// TODO: Move more stuff here
open class EnPassantProguardTask : ProGuardTask() {
    fun getConfiguration(): Configuration = configuration
}
