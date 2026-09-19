package com.lxmusic.android

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class SearchRepository {
    suspend fun search(keyword: String, source: String, limit: Int = 25): List<Song> = coroutineScope {
        val targets = when (source) { "all" -> listOf("kw","kg","tx","wy","mg"); else -> listOf(source) }
        targets.map { s -> async(Dispatchers.IO) { runCatching { searchOne(s,keyword,limit) }.getOrDefault(emptyList()) } }.awaitAll().flatten()
    }

    private suspend fun searchOne(source: String, keyword: String, limit: Int): List<Song> = when (source) {
        "kw" -> searchKw(keyword, limit)
        "kg" -> searchKg(keyword, limit)
        "tx" -> searchTx(keyword, limit)
        "wy" -> searchWy(keyword, limit)
        "mg" -> searchMg(keyword, limit)
        else -> emptyList()
    }

    private fun searchKw(q: String, limit: Int): List<Song> {
        val url = "http://search.kuwo.cn/r.s?client=kt&all=${HttpUtil.enc(q)}&pn=0&rn=$limit&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1"
        repeat(2) { try {
            val r = HttpUtil.get(url)
            if (r.status !in 200..299) return@repeat
            val arr = JSONObject(r.body).optJSONArray("abslist") ?: return@repeat
            return parseKw(arr)
        } catch (_: Exception) {} }
        return emptyList()
    }

    private fun parseKw(arr: JSONArray): List<Song> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val types = mutableListOf<Quality>()
            val mini = o.optString("N_MINFO")
            Regex("level:(\\w+),bitrate:(\\d+),format:(\\w+),size:([\\w.]+)").findAll(mini).forEach { m ->
                val type = when(m.groupValues[2]) { "4000" -> "flac24bit"; "2000" -> "flac"; "320" -> "320k"; "128" -> "128k"; else -> "" }
                if (type.isNotEmpty()) types += Quality(type,m.groupValues[4])
            }
            add(Song(o.optString("NAME"), cleanSinger(o.optString("ARTIST")), o.optString("ALBUM"), o.optString("ALBUMID"), o.optString("DC_TARGETID"), "kw", formatMs(o.optLong("DURATION",0)*1000), o.optString("PIC"), types.distinctBy { it.type }, o.toString()))
        }
    }

    private fun searchKg(q: String, limit: Int): List<Song> {
        val url = "http://songsearch.kugou.com/song_search_v2?platform=AndroidFilter&iscorrection=1&keyword=${HttpUtil.enc(q)}&hifiquality=0&pagesize=$limit&PrivilegeFilter=0&page=1"
        repeat(3) { try {
            val r = HttpUtil.get(url)
            if (r.status !in 200..299) return@repeat
            val lists = JSONObject(r.body).optJSONObject("data")?.optJSONArray("lists") ?: return@repeat
            return parseKg(lists)
        } catch (_: Exception) {} }
        return emptyList()
    }

    private fun parseKg(arr: JSONArray): List<Song> = buildList {
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("Audioid")
            val h = o.optString("FileHash")
            if (!seen.add("$id:$h")) continue
            val ts = mutableListOf<Quality>()
            if (o.optLong("FileSize") != 0L) ts += Quality("128k", o.optString("FileSize"))
            if (o.optLong("HQFileSize") != 0L) ts += Quality("320k", o.optString("HQFileSize"))
            if (o.optLong("SQFileSize") != 0L) ts += Quality("flac", o.optString("SQFileSize"))
            if (o.optLong("ResFileSize") != 0L) ts += Quality("flac24bit", o.optString("ResFileSize"))
            add(Song(stripTag(o.optString("SongName")), cleanSinger(o.optString("SingerName")), o.optString("AlbumName"), o.optString("AlbumID"), id, "kg", formatMs(o.optLong("Duration",0)*1000), o.optString("ImageUrl"), ts, o.toString()))
        }
    }

    private fun searchTx(q: String, limit: Int): List<Song> {
        val bodyObj = JSONObject().apply {
            put("comm", JSONObject().apply { put("_channelid","0"); put("_os_version","6.2.9200-2"); put("ct","19"); put("cv","2151"); put("guid","1F70E520B2EAA7D25E11760783C53CA9"); put("patch","118"); put("tmeAppID","qqmusic"); put("tmeLoginType",0); put("uin","0"); put("wid","7223299733393904640") })
            put("music.search.SearchCgiService", JSONObject().apply { put("module","music.search.SearchCgiService"); put("method","DoSearchForQQMusicDesktop"); put("param", JSONObject().apply { put("grp",1); put("num_per_page",limit); put("page_num",1); put("query",q); put("remoteplace","txt.newclient.top"); put("search_type",0); put("searchid",searchId()) }) })
        }
        val body = bodyObj.toString()
        val sign = zzcSign(body)
        val url = "https://u.y.qq.com/cgi-bin/musics.fcg?sign=$sign"
        repeat(5) { try {
            val r = HttpUtil.post(url,body,mapOf("User-Agent" to "QQMusic 14090508(android 12)","Content-Type" to "application/json"))
            if (r.status !in 200..299) return@repeat
            val root = JSONObject(r.body)
            val service = root.optJSONObject("music.search.SearchCgiService")
            val data = service?.optJSONObject("data") ?: root.optJSONObject("data") ?: root
            val song = data.optJSONObject("body")?.optJSONObject("song") ?: data.optJSONObject("song")
            val arr = song?.optJSONArray("list") ?: findArray(root,"list") ?: return@repeat
            return parseTx(arr)
        } catch (_: Exception) {} }
        return emptyList()
    }

    private fun parseTx(arr: JSONArray): List<Song> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val singer = o.optJSONArray("singer")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }.joinToString(" & ") } ?: o.optString("singer")
            val sizeMap = listOf("size_128mp3" to "128k","size_320mp3" to "320k","size_flac" to "flac","size_hires" to "flac24bit")
            val ts = sizeMap.mapNotNull { (k,t) -> if (o.optLong(k) > 0) Quality(t,o.optString(k)) else null }
            add(Song(o.optString("songname"), singer, o.optString("albumname"), o.optString("albumid"), o.optString("songmid"), "tx", formatSec(o.optInt("interval",0)), null, ts, o.toString()))
        }
    }

    private fun searchWy(q: String, limit: Int): List<Song> {
        val path = "/api/search/song/list/page"
        val obj = JSONObject().apply { put("keyword",q); put("needCorrect","1"); put("channel","typing"); put("offset",0); put("scene","normal"); put("total",true); put("limit",limit) }
        val enc = eapi(path,obj)
        repeat(3) { try {
            val r = HttpUtil.post("http://interface.music.163.com/eapi/batch", "params=$enc", mapOf("User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36", "origin" to "https://music.163.com", "Content-Type" to "application/x-www-form-urlencoded"))
            if (r.status !in 200..299) return@repeat
            val root = JSONObject(r.body)
            val resources = findArray(root,"resources") ?: return@repeat
            return parseWy(resources)
        } catch (_: Exception) {} }
        return emptyList()
    }

    private fun parseWy(arr: JSONArray): List<Song> = buildList {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i)?.optJSONObject("item") ?: continue
            val id = item.optString("id")
            val name = item.optString("name")
            val artists = item.optJSONArray("artists")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }.joinToString(" & ") }.orEmpty()
            val album = item.optJSONObject("album")
            val privilege = item.optJSONObject("privilege")
            val maxbr = privilege?.optInt("maxbr",0) ?: 0
            val size = if (maxbr == 999000) Quality("flac", item.optJSONObject("size")?.toString().orEmpty()) else null
            val ts = buildList {
                if (maxbr == 128000 || maxbr == 192000) add(Quality("128k"))
                if (maxbr == 320000) add(Quality("320k"))
                if (size != null) add(size)
                if (privilege?.optString("maxBrLevel") == "hires") add(Quality("flac24bit"))
            }
            add(Song(name,artists,album?.optString("name").orEmpty(),album?.optString("id").orEmpty(),id,"wy",formatSec(item.optInt("duration",0)/1000),null,ts,item.toString()))
        }
    }

    private fun searchMg(q: String, limit: Int): List<Song> {
        val time = System.currentTimeMillis().toString()
        val deviceId = "963B7AA0D21511ED807EE5846EC87D20"
        val sign = md5(q+"6cdc72a439cef99a3418d2a78aa28c73"+"yyapp2d16148780a1dcc7408e06336b98cfd50"+deviceId+time)
        val switch = "{\"song\":1,\"album\":0,\"singer\":0,\"tagSong\":1,\"mvSong\":0,\"bestShow\":1,\"songlist\":0,\"lyricSong\":0}"
        val url = "https://jadeite.migu.cn/music_search/v3/search/searchAll?isCorrect=0&isCopyright=1&searchSwitch=${HttpUtil.enc(switch)}&pageSize=$limit&text=${HttpUtil.enc(q)}&pageNo=1&sort=0&sid=USS"
        val h = mapOf("uiVersion" to "A_music_3.6.1","deviceId" to deviceId,"timestamp" to time,"sign" to sign,"channel" to "0146921","User-Agent" to "Mozilla/5.0 (Linux; U; Android 11.0; zh-cn)")
        repeat(3) { try {
            val r=HttpUtil.get(url,h)
            if(r.status !in 200..299)return@repeat
            val root=JSONObject(r.body); val arr=findArray(root,"resultList") ?: return@repeat
            return parseMg(arr)
        } catch (_:Exception) {} }
        return emptyList()
    }

    private fun parseMg(arr: JSONArray): List<Song> = buildList {
        for(i in 0 until arr.length()){
            val x=arr.opt(i); val o = when(x){ is JSONObject -> x; else -> null } ?: continue
            val formats=findArray(o,"audioFormats")
            val ts=formats?.let { a -> buildList { for(j in 0 until a.length()){ val f=a.optJSONObject(j) ?: continue; when(f.optString("formatType")){"PQ"->add(Quality("128k"));"HQ"->add(Quality("320k"));"SQ"->add(Quality("flac"));"ZQ24"->add(Quality("flac24bit"))}}}} ?: emptyList()
            add(Song(o.optString("songName",o.optString("name")),o.optString("singerName",o.optString("singer")),o.optString("albumName"),o.optString("albumId"),o.optString("songId"),"mg",formatSec(o.optInt("duration",0)),o.optString("albumImg",null),ts,o.toString()))
        }
    }

    suspend fun findMusic(target: Song, limit: Int = 25): List<Song> {
        val all = search("${target.name} ${target.singer}".trim(),"all",limit)
        return all.sortedByDescending { score(target,it) }
    }

    private fun score(a: Song,b: Song): Int {
        var s=0
        val an=norm(a.name); val bn=norm(b.name); val asg=normSinger(a.singer); val bsg=normSinger(b.singer)
        if(an==bn && asg==bsg) s+=1000
        else if(asg==bsg && (an.contains(bn)||bn.contains(an))) s+=800
        else if(norm(a.albumName)==norm(b.albumName)&&asg==bsg&&(an.contains(bn)||bn.contains(an))) s+=700
        if(a.interval.isNotBlank()&&b.interval.isNotBlank()&&abs(sec(a.interval)-sec(b.interval))<=5)s+=300
        if(an==bn)s+=100
        if(asg==bsg)s+=80
        return s
    }

    companion object {
        fun norm(s:String)=s.lowercase().replace(Regex("[\\s\\p{Punct}，。！？、（）【】『』‘’“”]"),"")
        fun normSinger(s:String)=s.split(Regex("\\s*&\\s*|/|、|，|,|\\s+" )).map{norm(it)}.filter{it.isNotEmpty()}.sorted().joinToString("&")
        fun sec(s:String):Int=s.split(":").let{if(it.size==2)it[0].toIntOrNull()?.times(60)?.plus(it[1].toIntOrNull()?:0)?:0 else 0}
        fun formatSec(s:Int)=if(s<=0)"" else "%d:%02d".format(s/60,s%60)
        fun formatMs(ms:Long)=formatSec((ms/1000).toInt())
        fun cleanSinger(s:String)=s.replace("&"," & ").replace(Regex("\\s+")," ").trim()
        fun stripTag(s:String)=s.replace(Regex("<em>.*?</em>"),"")
        fun md5(s:String)=MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString(""){ "%02x".format(it.toInt() and 0xff) }
        fun sha1(s:String)=MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString(""){ "%02x".format(it.toInt() and 0xff) }
        fun searchId():String=(1..32).joinToString(""){ "%x".format((0..15).random()) }.uppercase()+ (0..99999).random().toString().padStart(5,'0')
        fun zzcSign(text:String):String{
            val hash=sha1(text)
            val p1=intArrayOf(23,14,6,36,16,40,7,19).joinToString(""){hash[it].toString()}
            val p2=intArrayOf(16,1,32,12,19,27,8,5).joinToString(""){hash[it].toString()}
            val scramble=intArrayOf(89,39,179,150,218,82,58,252,177,52,186,123,120,64,242,133,143,161,121,179)
            val bytes=ByteArray(20){i->(scramble[i] xor hash.substring(i*2,i*2+2).toInt(16)).toByte()}
            val b64=Base64.encodeToString(bytes,Base64.NO_WRAP).filterNot{it in "\\/+= "}
            return ("zzc"+p1+b64+p2).lowercase()
        }
        fun eapi(path:String,obj:JSONObject):String{
            val text=obj.toString()
            val digest=md5("nobody${path}use${text}md5forencrypt")
            val data="${path}-36cd479b6b5-${text}-36cd479b6b5-${digest}"
            val cipher=Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec("e82ckenh8dichen8".toByteArray(StandardCharsets.UTF_8),"AES"))
            return cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8)).joinToString(""){ "%02X".format(it.toInt() and 0xff) }
        }
        fun findArray(root:JSONObject,key:String):JSONArray?{
            fun walk(v:Any?):JSONArray?{
                when(v){
                    is JSONObject->{if(v.has(key)&&v.opt(key) is JSONArray)return v.optJSONArray(key);val keys=v.keys();while(keys.hasNext()){val r=walk(v.opt(keys.next()));if(r!=null)return r}}
                    is JSONArray->{for(i in 0 until v.length()){val r=walk(v.opt(i));if(r!=null)return r}}
                };return null
            };return walk(root)
        }
    }
}
