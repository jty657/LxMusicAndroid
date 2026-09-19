package com.lxmusic.android

import android.content.Context
import org.json.JSONObject

class UrlCache(context: Context) {
    private val sp=context.getSharedPreferences("url_cache",Context.MODE_PRIVATE)
    fun key(song:Song,quality:String)="${song.source}|${song.songId}|$quality"
    fun get(song:Song,quality:String):String?=sp.getString(key(song,quality),null)
    fun put(song:Song,quality:String,url:String){sp.edit().putString(key(song,quality),url).apply()}
    fun remove(song:Song,quality:String){sp.edit().remove(key(song,quality)).apply()}
    fun clear(){sp.edit().clear().apply()}
}
