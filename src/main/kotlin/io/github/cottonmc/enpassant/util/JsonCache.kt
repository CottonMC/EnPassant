package io.github.cottonmc.enpassant.util

import blue.endless.jankson.Jankson
import blue.endless.jankson.JsonObject
import java.io.InputStream

/** Used for caching parsed JSON resources. */
internal class JsonCache(private val inputGetter: (String) -> InputStream) {
    private val jankson = Jankson.builder().build()
    private val cache: MutableMap<String, JsonObject> = HashMap()

    operator fun get(path: String): JsonObject =
        cache.getOrPut(path) { inputGetter(path).use { jankson.load(it) } }
}
