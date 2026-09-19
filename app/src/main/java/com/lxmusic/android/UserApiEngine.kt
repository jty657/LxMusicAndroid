package com.lxmusic.android

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class UserApiEngine(private val context: Context, private val webView: WebView) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val requestJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val listeners = mutableListOf<(SourceCapability) -> Unit>()
    private val capabilities = ConcurrentHashMap<String, SourceCapability>()
    @Volatile var loaded = false
        private set
    @Volatile var scriptError: String? = null
        private set

    init { setupWebView() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = false
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.allowFileAccessFromFileURLs = false
        webView.settings.allowUniversalAccessFromFileURLs = false
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) = null
        }
        webView.addJavascriptInterface(Bridge(), "Native")
        webView.loadUrl("file:///android_asset/user_api.html")
    }

    fun onCapability(listener: (SourceCapability) -> Unit) { listeners += listener }
    fun supports(source: String): Boolean = capabilities[source]?.actions?.contains("musicUrl") == true
    fun supportsQuality(source: String, quality: String): Boolean = capabilities[source]?.qualitys?.contains(quality) == true

    fun load(meta: SourceMeta, rawScript: String) {
        loaded = false; scriptError = null; capabilities.clear()
        val b64 = Base64.encodeToString(rawScript.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val m = JSONObject().apply {
            put("id", meta.id); put("name", meta.name); put("description", meta.description)
            put("author", meta.author); put("version", meta.version); put("homepage", meta.homepage)
        }
        webView.post { webView.evaluateJavascript("window.__loadUserScript('$b64',${m})", null) }
    }

    suspend fun getMusicUrl(song: Song, quality: String): String {
        check(loaded) { scriptError ?: "音源尚未加载完成" }
        val id = "request__${UUID.randomUUID().toString().replace("-", "")}";
        val d = CompletableDeferred<String>()
        pending[id] = d
        val data = JSONObject().apply {
            put("source", song.source); put("action", "musicUrl")
            put("info", JSONObject().apply { put("type", quality); put("musicInfo", song.musicInfoJson(quality)) })
        }.toString()
        webView.post { webView.evaluateJavascript("window.__dispatchRequest(${JSONObject.quote(id)},${data})", null) }
        return try {
            withTimeout(20_000) { d.await() }
                .also { require(it.length <= 2048 && Regex("^https?://").containsMatchIn(it)) { "音源返回了无效播放链接" } }
        } finally { pending.remove(id) }
    }

    fun cancelAll() {
        requestJobs.values.forEach { it.cancel() }; requestJobs.clear()
        pending.values.forEach { it.cancel() }; pending.clear()
    }

    inner class Bridge {
        @JavascriptInterface fun post(message: String) {
            scope.launch {
                runCatching { JSONObject(message) }.onSuccess { o ->
                    when (o.optString("t")) {
                        "loaded" -> loaded = true
                        "scriptError" -> { scriptError = o.optString("error"); loaded = false }
                        "response" -> {
                            val id=o.optString("id"); val d=pending[id] ?: return@launch
                            if(o.has("error")) d.completeExceptionally(IllegalStateException(o.optString("error")))
                            else { val r=o.optString("result"); if(!Regex("^https?://").containsMatchIn(r) || r.length>2048) d.completeExceptionally(IllegalStateException("无效 URL")) else d.complete(r) }
                        }
                        "send" -> if(o.optString("eventName")=="inited") parseInited(o.optJSONObject("data"))
                    }
                }
            }
        }

        @JavascriptInterface fun request(id: String, url: String, optionsJson: String) {
            val job=scope.launch(Dispatchers.IO) {
                try {
                    val o=JSONObject(optionsJson)
                    val method=o.optString("method","get").uppercase()
                    val headers=mutableMapOf<String,String>(); o.optJSONObject("headers")?.keys()?.forEach { k -> headers[k]=o.optJSONObject("headers")!!.optString(k) }
                    var body:String?=o.optString("body",null)
                    if(o.has("form")) { headers.putIfAbsent("Content-Type","application/x-www-form-urlencoded"); body=formUrl(o.optJSONObject("form")) }
                    if(o.has("formData")) { val pair=multiPart(o.optJSONObject("formData") ?: JSONObject()); headers["Content-Type"]=pair.first; body=pair.second }
                    val r=HttpUtil.request(url,method,headers,body,60_000)
                    val resp=JSONObject().apply { put("statusCode",r.status); put("statusMessage",r.statusMessage); put("headers",JSONObject(r.headers)); put("bytes",r.raw.size); put("raw",Base64.encodeToString(r.raw,Base64.NO_WRAP)) }
                    val bodyJson=JSONObject.quote(r.body)
                    webView.post { webView.evaluateJavascript("window.__requestResponse(${JSONObject.quote(id)},null,${resp},$bodyJson)",null) }
                } catch(e:Exception) {
                    webView.post { webView.evaluateJavascript("window.__requestResponse(${JSONObject.quote(id)},${JSONObject.quote(e.message?:e.javaClass.simpleName)},null,null)",null) }
                }
            }
            requestJobs[id]=job
        }

        @JavascriptInterface fun cancel(id:String){ requestJobs.remove(id)?.cancel() }
        @JavascriptInterface fun md5(s:String)=hex(MessageDigest.getInstance("MD5").digest(s.toByteArray()))
        @JavascriptInterface fun sha1(s:String)=hex(MessageDigest.getInstance("SHA-1").digest(s.toByteArray()))
        @JavascriptInterface fun randomBytes(size:String):String { val n=size.toIntOrNull()?.coerceIn(0,1_000_000)?:0; val b=ByteArray(n); SecureRandom().nextBytes(b); return Base64.encodeToString(b,Base64.NO_WRAP) }
        @JavascriptInterface fun bufferFromString(s:String,enc:String):String {
            return when(enc.lowercase()){
                "base64" -> s
                "hex" -> { val clean=s.replace(Regex("\\s"),""); Base64.encodeToString(ByteArray(clean.length/2){clean.substring(it*2,it*2+2).toInt(16).toByte()},Base64.NO_WRAP) }
                else -> Base64.encodeToString(s.toByteArray(Charsets.UTF_8),Base64.NO_WRAP)
            }
        }
        @JavascriptInterface fun bufToString(b64:String,format:String):String {
            val b=runCatching{Base64.decode(b64,Base64.DEFAULT)}.getOrDefault(ByteArray(0))
            return when(format.lowercase()) { "hex"->hex(b); "base64"->Base64.encodeToString(b,Base64.NO_WRAP); else->b.toString(Charsets.UTF_8) }
        }
        @JavascriptInterface fun aesEncrypt(b64:String,mode:String,keyB64:String,ivB64:String):String {
            val data=Base64.decode(b64,Base64.DEFAULT)
            fun decodeParam(v:String):ByteArray=when{
                v.startsWith("@b64:") -> Base64.decode(v.removePrefix("@b64:"),Base64.DEFAULT)
                v.startsWith("@str:") -> v.removePrefix("@str:").toByteArray(Charsets.UTF_8)
                else -> v.toByteArray(Charsets.UTF_8)
            }
            val key=decodeParam(keyB64); val iv=decodeParam(ivB64)
            val algo=when(mode.lowercase()){ "aes-128-cbc","aes-192-cbc","aes-256-cbc"->"AES/CBC/PKCS5Padding"; else->"AES/ECB/PKCS5Padding" }
            val cipher=Cipher.getInstance(algo); val spec=SecretKeySpec(key,"AES")
            if(algo.contains("CBC")) cipher.init(Cipher.ENCRYPT_MODE,spec,IvParameterSpec(if(ivB64.isBlank()) ByteArray(16) else iv)) else cipher.init(Cipher.ENCRYPT_MODE,spec)
            return Base64.encodeToString(cipher.doFinal(data),Base64.NO_WRAP)
        }
        @JavascriptInterface fun rsaEncrypt(b64:String,key:String):String {
            val clean=key.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replace(Regex("\\s"),"")
            val publicKey=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(clean,Base64.DEFAULT)))
            val cipher=Cipher.getInstance("RSA/ECB/PKCS1Padding"); cipher.init(Cipher.ENCRYPT_MODE,publicKey)
            return Base64.encodeToString(cipher.doFinal(Base64.decode(b64,Base64.DEFAULT)),Base64.NO_WRAP)
        }
        @JavascriptInterface fun zlibDeflate(b64:String)=compress(Base64.decode(b64,Base64.DEFAULT),true)
        @JavascriptInterface fun zlibInflate(b64:String)=compress(Base64.decode(b64,Base64.DEFAULT),false)
    }

    private fun parseInited(data: JSONObject?) {
        if(data==null)return
        val sources=data.optJSONObject("sources") ?: return
        sources.keys().forEach { id ->
            val s=sources.optJSONObject(id) ?: return@forEach
            val cap=SourceCapability(s.optString("type"),jsonStrings(s.optJSONArray("actions")),jsonStrings(s.optJSONArray("qualitys")))
            val finalCap=cap.copy(); capabilities[id]=finalCap; listeners.forEach { it(finalCap) }
        }
        loaded=true
    }

    private fun jsonStrings(a: JSONArray?): Set<String> = buildSet { if(a!=null) for(i in 0 until a.length()) add(a.optString(i)) }
    private fun hex(b:ByteArray)=b.joinToString(""){ "%02x".format(it.toInt() and 255) }
    private fun formUrl(o:JSONObject)=buildString { val ks=o.keys().asSequence().toList(); ks.forEachIndexed{idx,k->{if(idx>0)append('&');append(HttpUtil.enc(k));append('=');append(HttpUtil.enc(o.optString(k)))}} }
    private fun multiPart(o:JSONObject):Pair<String,String>{
        val boundary="----Lx${UUID.randomUUID().toString().replace("-","")}"; val out=StringBuilder()
        o.keys().forEach{ k -> out.append("--$boundary\r\nContent-Disposition: form-data; name=\"").append(k).append("\"\r\n\r\n").append(o.optString(k)).append("\r\n") }
        out.append("--$boundary--\r\n"); return "multipart/form-data; boundary=$boundary" to out.toString()
    }
    private fun compress(data:ByteArray,deflate:Boolean):String{
        return if(deflate){ val d=Deflater(); d.setInput(data); d.finish(); val out=ByteArrayOutputStream(); val buf=ByteArray(8192); while(!d.finished()) out.write(buf,0,d.deflate(buf)); d.end(); Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP) }
        else { val i=Inflater(); i.setInput(data); val out=ByteArrayOutputStream(); val buf=ByteArray(8192); while(!i.finished()&&!i.needsDictionary()&&!i.needsInput()) out.write(buf,0,i.inflate(buf)); i.end(); Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP) }
    }
}
