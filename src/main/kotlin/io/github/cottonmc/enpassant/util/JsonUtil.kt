package io.github.cottonmc.enpassant.util

import blue.endless.jankson.*

internal object JsonUtil {
    fun getEntrypointValues(cache: JsonCache): List<String> =
        (cache["fabric.mod.json"]["entrypoints"] as? JsonObject ?: emptyMap<String, JsonElement>())
            .values
            .asSequence()
            .flatMap { getEntrypointValues(it) }
            .toList()

    private fun getEntrypointValues(json: JsonElement): Sequence<String> =
        when (json) {
            is JsonPrimitive -> sequenceOf(json.asString())
            is JsonArray -> json.asSequence().flatMap { getEntrypointValues(it) }
            is JsonObject -> getEntrypointValues(
                json["value"] ?: throw IllegalArgumentException("Entrypoint JSON objects require the 'value' key")
            )

            is JsonNull -> throw IllegalArgumentException("Can't get entrypoints from a null value!")
            else -> throw IllegalArgumentException("The following JSON has an unknown type: $json")
        }

    fun getMixinJsonPaths(cache: JsonCache): List<String> =
        (cache["fabric.mod.json"]["mixins"] as? JsonArray ?: emptyList<JsonElement>())
            .map {
                when (it) {
                    is JsonPrimitive -> it.asString()
                    is JsonObject ->
                        (it["config"] as? JsonPrimitive
                            ?: throw IllegalArgumentException("'mixins' entry object must have a 'config' primitive"))
                            .asString()

                    else -> throw IllegalArgumentException("'mixins' entry must be an object or a primitive")
                }
            }

    fun getMixinRefmapPaths(cache: JsonCache): List<String> =
        getMixinJsonPaths(cache).mapNotNull {
            cache[it][String::class.java, "refmap"]
        }

    fun getMixinPackage(cache: JsonCache, path: String): String =
        (cache[path]["package"] as? JsonPrimitive
            ?: throw IllegalArgumentException("Mixin 'package' key must be a JSON primitive")).asString()
}