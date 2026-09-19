package com.lxmusic.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private lateinit var searchBox: EditText
    private lateinit var sourceSpinner: Spinner
    private lateinit var qualitySpinner: Spinner
    private lateinit var resultsBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var nowText: TextView
    private lateinit var webView: WebView
    private lateinit var engine: UserApiEngine
    private lateinit var store: SourceStore
    private lateinit var player: PlayerController
    private lateinit var urlCache: UrlCache
    private val repo=SearchRepository()
    private var sources=emptyList<SourceMeta>()
    private val qualityOptions=listOf("128k","320k","flac","flac24bit","hires","atmos","atmos_plus","master")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store=SourceStore(this); player=PlayerController(this); urlCache=UrlCache(this)
        buildUi()
        engine=UserApiEngine(this,webView)
        engine.onCapability { cap -> statusText.text="音源：${cap.type} / ${cap.actions.joinToString()} / ${cap.qualitys.joinToString()}" }
        loadSourceList()
    }

    private fun buildUi(){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,16,16,12);setBackgroundColor(Color.WHITE)}
        val title=TextView(this).apply{text="落雪音乐 Android";textSize=22f;setTextColor(Color.BLACK);setPadding(0,0,0,10)}
        root.addView(title)
        val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        searchBox=EditText(this).apply{hint="搜索歌曲 / 歌手";singleLine=true}
        row.addView(searchBox,LinearLayout.LayoutParams(0,52,1f))
        val search=Button(this).apply{text="搜索";setOnClickListener{doSearch()}}
        row.addView(search,LinearLayout.LayoutParams(86,52))
        root.addView(row)
        val filterRow=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        sourceSpinner=Spinner(this); sourceSpinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("全部","酷我","酷狗","QQ","网易云","咪咕"))
        filterRow.addView(sourceSpinner,LinearLayout.LayoutParams(0,52,1f))
        qualitySpinner=Spinner(this); qualitySpinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,qualityOptions)
        filterRow.addView(qualitySpinner,LinearLayout.LayoutParams(0,52,1f))
        val import=Button(this).apply{text="导入 JS";setOnClickListener{pickJs()}}
        filterRow.addView(import,LinearLayout.LayoutParams(100,52))
        val manage=Button(this).apply{text="音源";setOnClickListener{showSources()}}
        filterRow.addView(manage,LinearLayout.LayoutParams(72,52))
        root.addView(filterRow)
        statusText=TextView(this).apply{text="未加载音源；请先导入 JS 音源";setTextColor(Color.DKGRAY);setPadding(0,8,0,8)}
        root.addView(statusText)
        resultsBox=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val scroll=ScrollView(this).apply{addView(resultsBox,ScrollView.LayoutParams(-1,-1))}
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val playerRow=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        nowText=TextView(this).apply{text="未播放";textSize=15f;setTextColor(Color.BLACK)}
        playerRow.addView(nowText,LinearLayout.LayoutParams(0,52,1f))
        val play=Button(this).apply{text="播放/暂停";setOnClickListener{player.toggle()}}
        playerRow.addView(play,LinearLayout.LayoutParams(110,52))
        root.addView(playerRow)
        webView=WebView(this).apply{visibility=View.INVISIBLE}
        root.addView(webView,LinearLayout.LayoutParams(1,1))
        setContentView(root)
    }

    private fun doSearch(){
        val q=searchBox.text.toString().trim(); if(q.isBlank())return
        resultsBox.removeAllViews(); statusText.text="搜索中…"
        scope.launch {
            val src=listOf("all","kw","kg","tx","wy","mg")[sourceSpinner.selectedItemPosition]
            val list=withContext(Dispatchers.IO){repo.search(q,src)}
            statusText.text="找到 ${list.size} 首"
            list.forEach{addSong(it)}
        }
    }

    private fun addSong(song:Song){
        val b=Button(this).apply{
            isAllCaps=false;gravity=Gravity.START or Gravity.CENTER_VERTICAL
            text="${song.name}\n${song.singer}${if(song.interval.isNotBlank())"  ${song.interval}" else ""} · ${song.source}"
            setOnClickListener{playSong(song)}
        }
        resultsBox.addView(b,LinearLayout.LayoutParams(-1,68).apply{setMargins(0,4,0,4)})
    }

    private fun playSong(target:Song){
        val qualityWanted=qualitySpinner.selectedItem.toString()
        scope.launch {
            statusText.text="获取播放链接：${target.name}"
            val tried=HashSet<String>()
            var candidates=listOf(target)
            while(candidates.isNotEmpty()){
                val song=candidates.removeAt(0)
                if(!tried.add(song.source))continue
                val ok=engine.loaded
                if(!ok){Toast.makeText(this@MainActivity,engine.scriptError?:("请先导入并加载音源"),Toast.LENGTH_SHORT).show();return@launch}
                if(!engine.supports(song.source)) {
                    val found=withContext(Dispatchers.IO){repo.findMusic(target,25)}
                    candidates=(found.filter{it.source!=song.source})+candidates
                    continue
                }
                val q=chooseQuality(song,qualityWanted)
                try{
                    val cached=urlCache.get(song,q)
                    val url=if(!cached.isNullOrBlank()) cached else engine.getMusicUrl(song,q).also { urlCache.put(song,q,it) }
                    player.play(song,url); nowText.text="${song.name} · ${song.singer} [$q/${song.source}]"; statusText.text="播放中"; return@launch
                }catch(e:Exception){
                    urlCache.remove(song,q)
                    statusText.text="${song.source} 获取失败，尝试其他源…"
                    val found=withContext(Dispatchers.IO){repo.findMusic(target,25)}
                    candidates=(found.filter{it.source!=song.source})+candidates
                }
            }
            statusText.text="没有可用播放链接"
        }
    }

    private fun chooseQuality(song:Song,wanted:String):String{
        val available=song.types.map{it.type}.toSet()
        if(wanted != "128k" && available.contains(wanted) && engine.supportsQuality(song.source,wanted)) return wanted
        if(wanted=="128k") return "128k"
        val order=listOf("flac24bit","flac","320k")
        val start=order.indexOf(wanted).takeIf{it>=0}?:0
        for(i in start until order.size) if(available.contains(order[i]) && engine.supportsQuality(song.source,order[i])) return order[i]
        return "128k"
    }

    private fun pickJs(){
        val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
            type="text/javascript"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/javascript","application/javascript","application/x-javascript","text/plain","*/*"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(i,99)
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=99||resultCode!=Activity.RESULT_OK)return
        val uri=data?.data?:return
        scope.launch{
            try{
                val script=withContext(Dispatchers.IO){
                    val name=uri.lastPathSegment.orEmpty().substringAfterLast("/")
                    if (name.isNotBlank() && !name.lowercase().endsWith(".js")) throw IllegalArgumentException("请选择 .js 音源文件")
                    contentResolver.openInputStream(uri)?.use{it.readBytes().toString(Charsets.UTF_8)}?:throw IllegalStateException("无法读取文件")
                }
                if (script.length > 2_000_000) throw IllegalArgumentException("JS 音源文件过大（最大 2 MB）")
                val h=store.parseHeader(script); val meta=store.save(h["name"].orEmpty(),h["description"].orEmpty(),h["author"].orEmpty(),h["version"].orEmpty(),h["homepage"].orEmpty(),script)
                loadSourceList(); engine.load(meta,script); statusText.text="正在加载：${meta.name}"
            }catch(e:Exception){Toast.makeText(this@MainActivity,e.message?:"导入失败",Toast.LENGTH_LONG).show()}
        }
    }
    private fun loadSourceList(){sources=store.list();if(sources.isNotEmpty()){engine.load(sources.last(),store.read(sources.last()));statusText.text="加载：${sources.last().name}"}}
    private fun showSources(){
        val names=sources.map{"${it.name}  ${it.version}"}.toTypedArray()
        if(names.isEmpty()){Toast.makeText(this,"还没有导入 JS 音源",Toast.LENGTH_SHORT).show();return}
        android.app.AlertDialog.Builder(this)
            .setTitle("已导入 JS 音源")
            .setItems(names){_,which->
                val m=sources[which]
                engine.load(m,store.read(m))
                statusText.text="加载：${m.name}"
            }
            .setNeutralButton("删除最后选择的音源",null)
            .setNegativeButton("关闭",null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        if (sources.isEmpty()) return@setOnClickListener
                        val labels=sources.map{"${it.name}  ${it.version}"}.toTypedArray()
                        android.app.AlertDialog.Builder(this)
                            .setTitle("删除 JS 音源")
                            .setItems(labels){_,index->
                                val m=sources[index]
                                android.app.AlertDialog.Builder(this)
                                    .setTitle("确认删除")
                                    .setMessage("删除 ${m.name} 后需要重新导入 JS 文件才能使用。")
                                    .setNegativeButton("取消",null)
                                    .setPositiveButton("删除"){_,_->
                                        store.delete(m)
                                        loadSourceList()
                                        statusText.text="已删除：${m.name}"
                                    }.show()
                            }.show()
                    }
                }
            }.show()
    }
    override fun onDestroy(){engine.cancelAll();webView.destroy();player.release();scope.cancel();super.onDestroy()}
}
