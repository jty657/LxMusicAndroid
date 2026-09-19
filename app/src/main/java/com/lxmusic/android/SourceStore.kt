package com.lxmusic.android

import android.content.Context
import java.io.File
import java.util.UUID

class SourceStore(private val context: Context) {
    private val dir = File(context.filesDir, "sources").apply { mkdirs() }
    private val index = File(dir, "index.tsv")

    fun list(): List<SourceMeta> {
        if (!index.exists()) return emptyList()
        return index.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 7) null else SourceMeta(p[0],p[1],p[2],p[3],p[4],p[5],p[6])
        }.filter { File(dir, it.file).exists() }
    }

    fun save(name: String, description: String, author: String, version: String, homepage: String, script: String): SourceMeta {
        val id = "user_api_${UUID.randomUUID().toString().replace("-", "").take(6)}"
        val file = "$id.js"
        File(dir, file).writeText(script, Charsets.UTF_8)
        val meta = SourceMeta(id, name.ifBlank { "未命名音源" }, description, author, version, homepage, file)
        val rows = list().toMutableList().apply { add(meta) }
        index.writeText(rows.joinToString("\n") { listOf(it.id,it.name,it.description,it.author,it.version,it.homepage,it.file).joinToString("\t") }, Charsets.UTF_8)
        return meta
    }

    fun read(meta: SourceMeta): String = File(dir, meta.file).readText(Charsets.UTF_8)

    fun delete(meta: SourceMeta) {
        File(dir, meta.file).delete()
        index.writeText(list().filterNot { it.id == meta.id }.joinToString("\n") { listOf(it.id,it.name,it.description,it.author,it.version,it.homepage,it.file).joinToString("\t") }, Charsets.UTF_8)
    }

    fun parseHeader(script: String): Map<String,String> {
        val block = Regex("^\\s*/\\*[\\s\\S]*?\\*/").find(script)?.value.orEmpty()
        if (block.isBlank()) throw IllegalArgumentException("无效的自定义源文件：缺少 /* ... */ 元信息头")
        fun get(key: String) = Regex("@${Regex.escape(key)}\\s+(.+)").find(block)?.groupValues?.get(1)?.trim().orEmpty()
        return mapOf("name" to get("name"), "description" to get("description"), "author" to get("author"), "version" to get("version"), "homepage" to get("homepage"))
    }
}
