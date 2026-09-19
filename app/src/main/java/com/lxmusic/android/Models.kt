package com.lxmusic.android

import org.json.JSONObject

data class Quality(val type: String, val size: String = "")

data class Song(
    val name: String,
    val singer: String,
    val albumName: String,
    val albumId: String,
    val songId: String,
    val source: String,
    val interval: String,
    val img: String? = null,
    val types: List<Quality> = emptyList(),
    val rawJson: String = "{}"
) {
    fun musicInfoJson(type: String): JSONObject = JSONObject(rawJson).apply {
        put("name", name)
        put("singer", singer)
        put("albumName", albumName)
        put("albumId", albumId)
        put("songmid", songId)
        put("songId", songId)
        put("source", source)
        put("interval", interval)
        put("type", type)
    }
}

data class SourceMeta(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val homepage: String,
    val file: String
)

data class SourceCapability(
    val type: String,
    val actions: Set<String>,
    val qualitys: Set<String>
)
