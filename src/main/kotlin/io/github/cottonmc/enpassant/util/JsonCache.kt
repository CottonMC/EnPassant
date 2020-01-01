package io.github.cottonmc.enpassant.util

import blue.endless.jankson.Jankson
import blue.endless.jankson.JsonObject
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject as GsonObject
import java.io.InputStream
import java.io.InputStreamReader

/** Used for caching parsed JSON resources. */
internal class JsonCache(private val inputGetter: (String) -> InputStream) {
    private val jankson = Jankson.builder().build()
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val cache: MutableMap<String, JsonObject> = HashMap()

    operator fun get(path: String): JsonObject =
        cache.getOrPut(path) { inputGetter(path).use { jankson.load(preprocess(it)) } }

    // Used to remove escape codes from the input.
    // See https://github.com/falkreon/Jankson/issues/31
    private fun preprocess(input: InputStream) =
        gson.toJson(gson.fromJson(InputStreamReader(input), GsonObject::class.java))
}
