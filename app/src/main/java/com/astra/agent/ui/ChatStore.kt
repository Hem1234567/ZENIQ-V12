package com.astra.agent.ui

import android.content.Context
import android.net.Uri
import com.astra.agent.agent.Msg
import com.astra.agent.llm.LoadedDoc
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatMeta(val id: String, val title: String, val updated: Long)

class LoadedChat(
    val title: String,
    val items: List<ChatItem>,
    val msgs: List<Msg>,
    val docs: List<LoadedDoc>
)

/** Saves every conversation as a JSON file so history survives app restarts. */
class ChatStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "chats").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")

    @Synchronized
    fun list(): List<ChatMeta> {
        return try {
            if (!indexFile.exists()) return emptyList()
            val arr = JSONArray(indexFile.readText())
            val out = ArrayList<ChatMeta>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (File(dir, o.getString("id") + ".json").exists())
                    out.add(ChatMeta(o.getString("id"), o.optString("title", "New chat"), o.optLong("updated", 0L)))
            }
            out.sortedByDescending { it.updated }
        } catch (_: Exception) { emptyList() }
    }

    private fun writeIndex(l: List<ChatMeta>) {
        val arr = JSONArray()
        l.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("updated", it.updated)) }
        atomicWrite(indexFile, arr.toString())
    }

    private fun atomicWrite(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    @Synchronized
    fun save(id: String, title: String, items: List<ChatItem>, msgs: List<Msg>, docs: List<LoadedDoc>) {
        try {
            val root = JSONObject().put("title", title)
            val ia = JSONArray()
            for (it in items) {
                when (it) {
                    is UserItem -> ia.put(JSONObject().put("t", "u").put("id", it.id).put("text", it.text)
                        .put("att", JSONArray().also { a -> it.attachments.forEach { x ->
                            a.put(JSONObject().put("n", x.name).put("p", x.pages).put("k", x.sizeKb).put("img", x.isImage)) } }))
                    is AssistantItem -> ia.put(JSONObject().put("t", "a").put("id", it.id)
                        .put("think", it.thinking).put("text", it.text))
                    is ToolItem -> ia.put(JSONObject().put("t", "t").put("id", it.id).put("name", it.name)
                        .put("args", it.args).put("result", it.result ?: "").put("ok", it.ok))
                    is InfoItem -> ia.put(JSONObject().put("t", "i").put("id", it.id).put("text", it.text).put("err", it.error))
                    is AppItem -> ia.put(JSONObject().put("t", "p").put("id", it.id).put("name", it.name)
                        .put("zip", it.zipPath).put("pub", it.publicPath).put("kb", it.sizeKb)
                        .put("files", JSONArray().also { a -> it.files.forEach { f -> a.put(f) } }))
                    is FileItem -> ia.put(JSONObject().put("t", "f").put("id", it.id).put("name", it.name).put("path", it.path)
                        .put("pub", it.publicPath).put("mime", it.mime).put("kind", it.kind).put("kb", it.sizeKb))
                    is DocItem -> {}
                }
            }
            root.put("items", ia)
            val ma = JSONArray()
            msgs.forEach { ma.put(JSONObject().put("r", it.role).put("c", it.content)) }
            root.put("msgs", ma)
            val da = JSONArray()
            docs.forEach { d ->
                da.put(JSONObject().put("n", d.name).put("p", d.pageCount).put("k", d.sizeKb)
                    .put("ok", d.readable).put("text", d.text))
            }
            root.put("docs", da)
            atomicWrite(File(dir, "$id.json"), root.toString())

            val meta = list().filter { it.id != id } + ChatMeta(id, title, System.currentTimeMillis())
            writeIndex(meta)
        } catch (_: Exception) { /* never crash the app because history could not be saved */ }
    }

    @Synchronized
    fun load(id: String): LoadedChat? {
        return try {
            val f = File(dir, "$id.json")
            if (!f.exists()) return null
            val root = JSONObject(f.readText())
            val items = ArrayList<ChatItem>()
            val ia = root.getJSONArray("items")
            for (i in 0 until ia.length()) {
                val o = ia.getJSONObject(i)
                when (o.optString("t")) {
                    "u" -> {
                        val att = ArrayList<AttachInfo>()
                        val aa = o.optJSONArray("att")
                        if (aa != null) for (k in 0 until aa.length()) {
                            val x = aa.getJSONObject(k)
                            att.add(AttachInfo(x.optString("n"), x.optInt("p"), x.optInt("k"), x.optBoolean("img", false), null))
                        }
                        items.add(UserItem(o.getString("id"), o.optString("text"), att))
                    }
                    "a" -> items.add(AssistantItem(o.getString("id"), o.optString("think"), o.optString("text"), false))
                    "t" -> items.add(ToolItem(o.getString("id"), o.optString("name"), o.optString("args"),
                        o.optString("result").ifBlank { null }, o.optBoolean("ok", true), false))
                    "p" -> {
                        val fl = ArrayList<String>()
                        val fa = o.optJSONArray("files")
                        if (fa != null) for (k in 0 until fa.length()) fl.add(fa.optString(k))
                        items.add(AppItem(o.getString("id"), o.optString("name"), o.optString("zip"), o.optString("pub"), fl, o.optInt("kb", 1)))
                    }
                    "f" -> items.add(FileItem(o.getString("id"), o.optString("name"), o.optString("path"), o.optString("pub"),
                        o.optString("mime"), o.optString("kind"), o.optInt("kb", 1)))
                    "i" -> items.add(InfoItem(o.getString("id"), o.optString("text"), o.optBoolean("err", false)))
                }
            }
            val msgs = ArrayList<Msg>()
            val ma = root.optJSONArray("msgs")
            if (ma != null) for (i in 0 until ma.length()) {
                val o = ma.getJSONObject(i); msgs.add(Msg(o.optString("r"), o.optString("c")))
            }
            val docs = ArrayList<LoadedDoc>()
            val da = root.optJSONArray("docs")
            if (da != null) for (i in 0 until da.length()) {
                val o = da.getJSONObject(i)
                docs.add(LoadedDoc(Uri.EMPTY, o.optString("n"), o.optString("text"), o.optInt("p", 1),
                    o.optInt("k", 1), o.optBoolean("ok", true)))
            }
            LoadedChat(root.optString("title", "Chat"), items, msgs, docs)
        } catch (_: Exception) { null }
    }

    @Synchronized
    fun delete(id: String) {
        try { File(dir, "$id.json").delete(); writeIndex(list().filter { it.id != id }) } catch (_: Exception) {}
    }

    @Synchronized
    fun rename(id: String, title: String) {
        try { writeIndex(list().map { if (it.id == id) it.copy(title = title) else it }) } catch (_: Exception) {}
    }
}
