package com.astra.agent.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.astra.agent.Settings
import com.astra.agent.SettingsStore
import com.astra.agent.agent.*
import com.astra.agent.code.AppStorage
import com.astra.agent.code.CodeIntent
import com.astra.agent.code.FileExtractor
import com.astra.agent.code.FileStorage
import com.astra.agent.code.ReqIntent
import com.astra.agent.device.AgentService
import com.astra.agent.docs.DocBuilder
import com.astra.agent.docs.DocFormat
import com.astra.agent.docs.DocImage
import com.astra.agent.docs.DocIntent
import com.astra.agent.docs.DocRequest
import com.astra.agent.docs.MdParser
import com.astra.agent.docs.StyleChoice
import com.astra.agent.llm.BigWork
import com.astra.agent.personal.Kv
import com.astra.agent.personal.MemoryIntent
import com.astra.agent.personal.Personal
import com.astra.agent.llm.MediaGen
import com.astra.agent.site.SiteContent
import com.astra.agent.site.SiteDesigns
import com.astra.agent.site.SiteIntent
import com.astra.agent.site.SiteRenderer
import com.astra.agent.llm.MediaIntent
import com.astra.agent.docs.StyleDetect
import com.astra.agent.docs.Themes
import com.astra.agent.docs.Tier
import com.astra.agent.llm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID

sealed interface ChatItem { val id: String }
data class AttachInfo(val name: String, val pages: Int, val sizeKb: Int, val isImage: Boolean = false, val thumb: android.graphics.Bitmap? = null)
class ImageAttach(val id: String, val name: String, val b64: String, val thumb: android.graphics.Bitmap)
data class UserItem(override val id: String, val text: String, val attachments: List<AttachInfo> = emptyList()) : ChatItem
data class AssistantItem(override val id: String, val thinking: String = "", val text: String = "", val streaming: Boolean = true) : ChatItem
data class ToolItem(override val id: String, val name: String, val args: String, val result: String? = null, val ok: Boolean = true, val waiting: Boolean = false) : ChatItem
data class InfoItem(override val id: String, val text: String, val error: Boolean) : ChatItem
data class DocItem(override val id: String, val docName: String, val pageCount: Int, val sizeKb: Int) : ChatItem

/** A generated app, packaged as a ZIP, shown as a download card in the chat. */
data class AppItem(override val id: String, val name: String, val zipPath: String, val publicPath: String,
                   val files: List<String>, val sizeKb: Int) : ChatItem

/** A document ZENIQ created (PDF / Word / Excel / PowerPoint / CSV ...), shown as a download card. */
data class FileItem(override val id: String, val name: String, val path: String, val publicPath: String,
                    val mime: String, val kind: String, val sizeKb: Int) : ChatItem

class Approval(val name: String, val args: String, val result: CompletableDeferred<Boolean>)

class AstraViewModel(private val app: Application) : AndroidViewModel(app) {
    val models  = ModelManager(app)
    val voice   = VoiceManager(app)
    val kokoro  = KokoroManager(app)
    val docMgr  = DocumentManager(app)

    private val store  = SettingsStore(app)
    private val tools  = ToolRegistry(app)
    private val agent  = Agent(tools)

    val settings     = MutableStateFlow(store.load())
    init { OpenRouterClient.configure(settings.value.provider, settings.value.providerBase) }
    val items        = MutableStateFlow<List<ChatItem>>(emptyList())
    val running      = MutableStateFlow(false)

    // What ZENIQ remembers about you, your projects and your writing style (added to the system prompt)
    val personal = Personal(object : Kv {
        private val sp = app.getSharedPreferences("zeniq_personal", android.content.Context.MODE_PRIVATE)
        override fun get(key: String): String? = sp.getString(key, null)
        override fun put(key: String, value: String) { sp.edit().putString(key, value).apply() }
    })
    val personalTick = MutableStateFlow(0)
    /** Text from another app (Share to ZENIQ) waiting to be put into the message box. */
    val draft = MutableStateFlow("")
    private fun effective(): Settings {
        val s = settings.value
        val extra = personal.promptExtra()
        return if (extra.isEmpty()) s else s.copy(systemPrompt = s.systemPrompt + extra)
    }

    fun attachShared(uri: Uri, mime: String?, name: String) {
        if ((mime ?: "").startsWith("image/")) loadImage(uri, name) else loadDocument(uri, name)
    }

    // ── Chat organising: pin, rename, export, full-text search ───────────────
    private val chatPrefs = app.getSharedPreferences("zeniq_chats", android.content.Context.MODE_PRIVATE)
    val pinned = MutableStateFlow((chatPrefs.getString("pinned", "") ?: "").split(",").filter { it.isNotBlank() }.toSet())

    fun togglePin(id: String) {
        val s = pinned.value.toMutableSet()
        if (!s.add(id)) s.remove(id)
        pinned.value = s
        chatPrefs.edit().putString("pinned", s.joinToString(",")).apply()
    }

    fun renameChatTo(id: String, title: String) {
        val t = title.trim().take(60)
        if (t.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { chatStore.rename(id, t); chats.value = chatStore.list() }
    }

    /** The whole conversation as Markdown, for sharing or saving. */
    suspend fun exportChatText(id: String): String? = withContext(Dispatchers.IO) {
        val lc = chatStore.load(id) ?: return@withContext null
        val sb = StringBuilder("# ").append(lc.title).append("\n\n")
        for (it in lc.items) when (it) {
            is UserItem -> sb.append("**You:** ").append(it.text).append("\n\n")
            is AssistantItem -> if (it.text.isNotBlank()) sb.append("**ZENIQ:** ").append(it.text).append("\n\n")
            else -> {}
        }
        sb.toString()
    }

    /** Ids of chats whose messages contain [q] (the title search is done on the screen). */
    suspend fun searchChatText(q: String): Set<String> = withContext(Dispatchers.IO) {
        chats.value.filter { meta ->
            chatStore.load(meta.id)?.items?.any { (it is UserItem && it.text.contains(q, true)) || (it is AssistantItem && it.text.contains(q, true)) } == true
        }.map { it.id }.toSet()
    }

    fun lastUserText(): String? = items.value.filterIsInstance<UserItem>().lastOrNull()?.text

    /** Removes the last question and everything after it, from the screen and from what the model remembers. */
    fun dropLastTurn() {
        if (running.value) return
        items.update { l -> val i = l.indexOfLast { it is UserItem }; if (i >= 0) l.subList(0, i).toList() else l }
        val hi = history.indexOfLast { it.role == "user" && !it.content.startsWith("<tool_response>") }
        if (hi >= 0) while (history.size > hi) history.removeAt(history.size - 1)
    }

    fun regenerate() {
        if (running.value) return
        val t = lastUserText() ?: return
        dropLastTurn(); send(t)
    }
    val loadedModel  = MutableStateFlow<String?>(null)
    val loadingModel = MutableStateFlow(false)
    val installed    = MutableStateFlow(models.installed())
    val approval     = MutableStateFlow<Approval?>(null)
    val liveStatus   = MutableStateFlow("")
    val ttsOn        = MutableStateFlow(settings.value.tts)   // voice stays OFF until the user turns it on
    /** Human-readable storage info, e.g. "3.2 GB used · 12.4 GB free" */
    val storageInfo  = MutableStateFlow("")
    /** Files picked by the user but NOT yet sent (shown above the prompt box like Claude). */
    val attached     = MutableStateFlow<List<LoadedDoc>>(emptyList())
    val attachedImages = MutableStateFlow<List<ImageAttach>>(emptyList())
    val monitor      = ResourceMonitor(app)
    private val chatStore = ChatStore(app)
    val chats        = MutableStateFlow<List<ChatMeta>>(emptyList())
    val activeChat   = MutableStateFlow(UUID.randomUUID().toString())
    private var currentTitle = ""
    private var titleGenerated = false
    val readingDoc   = MutableStateFlow<String?>(null)
    /** Live list of free OpenRouter models for the Settings picker. */
    val freeModels   = MutableStateFlow(OpenRouterClient.defaultFree)
    val modelsStatus = MutableStateFlow("")

    private val history = mutableListOf<Msg>()
    /** What the last message asked for: normal chat, plain code, or a whole app (-> ZIP). */
    @Volatile private var currentIntent = ReqIntent.CHAT
    @Volatile private var lastRequest = ""
    @Volatile private var currentDocReq: DocRequest? = null
    /** Names of generated files that were registered as editable documents (only the newest is kept). */
    private val generatedDocNames = mutableSetOf<String>()
    private var agentJob: Job? = null
    private val dlJobs = mutableMapOf<String, Job>()

    init {
        val last = settings.value.lastModel
        // Sync loadedName immediately so the UI shows "Active" before the async load finishes
        if (LlmEngine.loadedName != null) loadedModel.value = LlmEngine.loadedName
        val fileToLoad = models.installed().firstOrNull { it.name == last }
        if (fileToLoad != null) loadModel(fileToLoad)
        refreshStorage()
        // Init Kokoro engine if model files are already downloaded
        if (kokoro.isDownloaded()) kokoro.initEngine()
        refreshFreeModels()
        chats.value = chatStore.list()
    }

    // ── Voice on/off ──────────────────────────────────────────────────────────
    fun setTts(on: Boolean) {
        ttsOn.value = on
        updateSettings(settings.value.copy(tts = on))
        if (!on) { try { voice.stopSpeaking(kokoro) } catch (_: Throwable) {} }
    }

    // ── Images ────────────────────────────────────────────────────────────────
    fun loadImage(uri: Uri, name: String = "Photo") {
        if (attachedImages.value.size >= 8) {
            add(InfoItem(uid(), "You can attach up to 8 images.", true)); return
        }
        val label = if (name == "Photo") "Photo ${attachedImages.value.size + 1}" else name
        viewModelScope.launch {
            readingDoc.value = name
            val r = withContext(Dispatchers.IO) { runCatching { ImageUtil.encode(app, uri) } }
            readingDoc.value = null
            r.onSuccess { pair ->
                attachedImages.update { l -> l + ImageAttach(uid(), label, pair.first, pair.second) }
            }.onFailure { e ->
                add(InfoItem(uid(), "Couldn't read the image: ${e.message}", true))
            }
        }
    }

    fun addError(msg: String) { add(InfoItem(uid(), msg, true)) }

    fun removeImage(id: String) { attachedImages.update { l -> l.filter { it.id != id } } }

    fun clearCache(): Long = monitor.clearCache()

    // ── Chat history ──────────────────────────────────────────────────────────
    private fun heuristicTitle(): String {
        val first = items.value.filterIsInstance<UserItem>().firstOrNull()?.text.orEmpty()
        val words = first.replace(Regex("\\s+"), " ").trim().split(" ").filter { it.isNotBlank() }.take(6)
        val t = words.joinToString(" ").take(40)
        return if (t.isBlank()) "New chat" else t.replaceFirstChar { it.uppercase() }
    }

    /** Writes the current conversation to disk (in the background). */
    private fun saveSnapshot() {
        val itemsCopy = items.value
        if (itemsCopy.none { it is UserItem }) return
        val id = activeChat.value
        val title = currentTitle.ifBlank { heuristicTitle() }
        val msgs = history.toList()
        val docs = docMgr.docs.value
        viewModelScope.launch(Dispatchers.IO) {
            chatStore.save(id, title, itemsCopy, msgs, docs)
            chats.value = chatStore.list()
        }
    }

    /** Gives the chat a short smart name (like Claude). Cloud mode asks the model; otherwise first words. */
    private fun maybeGenerateTitle() {
        if (titleGenerated) { saveSnapshot(); return }
        val firstUser = items.value.filterIsInstance<UserItem>().firstOrNull()?.text ?: return
        val firstAns = items.value.filterIsInstance<AssistantItem>().firstOrNull { it.text.isNotBlank() }?.text.orEmpty()
        titleGenerated = true
        currentTitle = heuristicTitle()
        saveSnapshot()
        val s = settings.value
        if (!(s.fastMode && s.openRouterKey.isNotBlank())) return
        val chatId = activeChat.value
        viewModelScope.launch(Dispatchers.IO) {
            var title = ""
            for (model in OpenRouterClient.candidates(s.openRouterModel).take(2)) {
                try {
                    val sb = StringBuilder()
                    OpenRouterClient.streamChat(
                        s.openRouterKey, model,
                        listOf(
                            OpenRouterClient.Message("system", "You name chats. Reply with ONLY a short title of 2 to 5 words. No quotes, no trailing punctuation."),
                            OpenRouterClient.Message("user", "User: ${firstUser.take(300)}\nAssistant: ${firstAns.take(300)}")
                        ), 24, 0.3f
                    ) { sb.append(it); true }
                    title = sb.toString()
                    if (title.isNotBlank()) break
                } catch (_: Exception) { }
            }
            val clean = title.replace(Regex("<think>.*?(</think>|$)", RegexOption.DOT_MATCHES_ALL), "")
                .trim().trim('"', '\'', '*', '#', '.').lines().firstOrNull().orEmpty().trim().take(40)
            if (clean.length >= 3) {
                if (activeChat.value == chatId) currentTitle = clean
                chatStore.rename(chatId, clean)
                chats.value = chatStore.list()
            }
        }
    }

    private fun resetState() {
        history.clear()
        attached.value = emptyList()
        attachedImages.value = emptyList()
        items.value = emptyList()
        liveStatus.value = ""
        docMgr.clear()
        currentTitle = ""
        titleGenerated = false
        activeChat.value = UUID.randomUUID().toString()
    }

    fun openChat(id: String) {
        if (id == activeChat.value) return
        stop()
        saveSnapshot()
        val data = chatStore.load(id) ?: run {
            add(InfoItem(uid(), "Couldn't open that chat.", true)); return
        }
        history.clear(); history.addAll(data.msgs)
        attached.value = emptyList()
        attachedImages.value = emptyList()
        docMgr.docs.value = data.docs
        items.value = data.items
        currentTitle = data.title
        titleGenerated = true
        activeChat.value = id
    }

    fun deleteChat(id: String) {
        chatStore.delete(id)
        chats.value = chatStore.list()
        if (id == activeChat.value) { stop(); resetState() }
    }

    fun refreshFreeModels() {
        viewModelScope.launch {
            modelsStatus.value = "Loading free models\u2026"
            val list = OpenRouterClient.fetchFreeModels()
            if (list.isEmpty()) {
                modelsStatus.value = "Couldn't reach OpenRouter \u2014 showing default list."
            } else {
                val withAuto = listOf(OpenRouterClient.defaultFree.first()) + list
                freeModels.value = withAuto
                OpenRouterClient.applyLive(list)
                modelsStatus.value = "${list.size} free models available"
            }
        }
    }

    fun setFastMode(on: Boolean) {
        updateSettings(settings.value.copy(fastMode = on))
        if (on && settings.value.openRouterKey.isBlank())
            add(InfoItem(uid(), "Fast Mode is on. Connect a provider (OpenRouter has free models) in Settings > Connect provider to start.", false))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun add(i: ChatItem) = items.update { list ->
        // Cap list at 200 items — remove oldest assistant/tool pairs to avoid memory bloat
        val newList = list + i
        if (newList.size > 200) newList.drop(newList.size - 200) else newList
    }
    private fun upd(id: String, f: (ChatItem) -> ChatItem) =
        items.update { l -> l.map { if (it.id == id) f(it) else it } }
    private fun uid() = UUID.randomUUID().toString()

    fun updateSettings(s: Settings) { settings.value = s; store.save(s) }

    // ── Local model speed test ──
    val speedText = MutableStateFlow("")
    fun runSpeedTest() {
        if (speedText.value == "Testing\u2026") return
        speedText.value = "Testing\u2026"
        viewModelScope.launch { speedText.value = try { LlmEngine.speedTest() } catch (e: Exception) { "The test failed: ${e.message}" } }
    }

    // ── Cloud providers (OpenRouter, Groq, Gemini, ... or any OpenAI-compatible service) ──
    fun savedKey(provider: String) = store.keyFor(provider)
    fun savedModel(provider: String) = store.modelFor(provider)
    fun savedBase(provider: String) = store.baseFor(provider)

    /** Makes [provider] the one Fast Mode uses. Each provider keeps its own key and model. */
    fun connectProvider(provider: String, baseUrl: String, key: String, model: String) {
        val p = Providers.byId(provider)
        OpenRouterClient.configure(provider, baseUrl)
        updateSettings(settings.value.copy(provider = provider, providerBase = baseUrl.trim(), openRouterKey = key.trim(),
            openRouterModel = model.trim().ifBlank { p.defaultModel }, fastMode = true))
        if (provider == Providers.OPENROUTER) refreshFreeModels()
    }

    private fun refreshStorage() {
        val used = "%.1f GB used".format(models.usedDiskGb())
        val free = "%.1f GB free".format(models.freeDiskGb)
        storageInfo.value = "$used · $free"
    }

    // ── Model loading ─────────────────────────────────────────────────────────
    fun loadModel(f: File) {
        if (loadingModel.value) {
            add(InfoItem(uid(), "Already loading a model. Wait for it to finish.", true)); return
        }
        if (LlmEngine.loadedName == f.name) {
            // Already loaded, just sync the state
            loadedModel.value = f.name; return
        }
        if (!f.exists() || f.length() < 1_000_000L) {
            add(InfoItem(uid(), "Model file not found or corrupted: ${f.name}", true)); return
        }
        viewModelScope.launch {
            loadingModel.value = true
            liveStatus.value = "Loading ${f.name.removeSuffix(".gguf")}…"
            val res = try {
                LlmEngine.loadDetailed(f, settings.value.nCtx, settings.value.threads)
            } catch (_: Throwable) {
                LlmEngine.LoadResult.Failed
            } finally {
                // Always clear loading flag no matter what
                loadingModel.value = false
                liveStatus.value = ""
            }
            val ok = res is LlmEngine.LoadResult.Ok
            loadedModel.value = if (ok) f.name else if (res is LlmEngine.LoadResult.Busy) loadedModel.value else null
            if (res is LlmEngine.LoadResult.Busy) return@launch      // another load is already running: nothing to report
            val mi = android.app.ActivityManager.MemoryInfo().also { (app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }
            if (res is LlmEngine.LoadResult.Ok) {
                val used = res.ctx
                updateSettings(settings.value.copy(lastModel = f.name, nCtx = minOf(settings.value.nCtx, used)))
                if (f.length() > mi.totalMem * 0.5) {
                    add(InfoItem(uid(), "${f.name.removeSuffix(".gguf")} is big for this phone (${fmtBytes(f.length())} of ${fmtBytes(mi.totalMem)} RAM), so replies will be slow. " +
                        "For fast answers use a smaller model (for example Qwen3 0.6B or 1.7B) or turn on \u26a1 Fast Mode.", false))
                }
                // Read the fixed start of the chat prompt in the background (never on the UI thread),
                // so the first answer starts sooner. It is skipped automatically if a question comes in first.
                val prefix = agent.warmupPrefix(effective())
                viewModelScope.launch(Dispatchers.Default) { try { LlmEngine.warmup(prefix) } catch (_: Throwable) {} }
            } else if (res is LlmEngine.LoadResult.Missing) {
                add(InfoItem(uid(), "Model file not found: ${f.name}. Download it again on the Models tab.", true))
            } else if (mi.availMem < f.length() + 300_000_000L) {
                add(InfoItem(uid(), "Not enough free RAM right now to load ${f.name.removeSuffix(".gguf")}: it needs about ${fmtBytes(f.length())} and only ${fmtBytes(mi.availMem)} is free. " +
                    "Close other apps and try again, or use a smaller model.", true))
            } else {
                add(InfoItem(uid(), "Couldn't load ${f.name}. The file may be damaged or not supported. Try downloading it again, or pick another model.", true))
            }
        }
    }

    // ── Downloads ─────────────────────────────────────────────────────────────
    fun download(url: String) {
        if (url.isBlank()) return
        if (dlJobs[url]?.isActive == true) return          // Already downloading this URL

        // Limit: max 2 concurrent downloads to avoid network + disk thrash
        val activeCount = dlJobs.values.count { it.isActive }
        if (activeCount >= 2) {
            add(InfoItem(uid(), "Max 2 downloads at once. Wait for one to finish before starting another.", false))
            return
        }

        // Disk space check
        val catalog = models.allModels.firstOrNull { it.url == url }
        if (catalog != null && models.freeDiskGb < catalog.sizeGb * 1.05) {
            add(InfoItem(uid(), "Not enough storage to download ${catalog.name} (${catalog.sizeLabel}). Free up ${catalog.sizeLabel} of storage first.", true))
            return
        }

        dlJobs[url] = viewModelScope.launch {
            AgentService.startDownload(app, "Downloading model…")
            try {
                models.download(url)
                installed.value = models.installed()
                refreshStorage()
                val f = models.fileFor(url)
                // Auto-load if no model is active yet
                if (f.exists() && f.length() > 1_000_000L && loadedModel.value == null && !loadingModel.value) {
                    loadModel(f)
                }
            } finally {
                dlJobs.remove(url)
                if (dlJobs.values.none { it.isActive }) AgentService.stopDownload(app)
                refreshStorage()
            }
        }
    }

    fun cancelDownload(url: String) {
        dlJobs[url]?.cancel()
        dlJobs.remove(url)
    }

    fun deletePartial(url: String)  {
        models.deletePartial(url)
        refreshStorage()
    }

    fun deleteModel(f: File) {
        if (LlmEngine.loadedName == f.name) {
            // Stop any running agent first
            stop()
            loadedModel.value = null
            // unloading waits for a running answer to stop, so it must never happen on the UI thread
            viewModelScope.launch(Dispatchers.Default) {
                LlmEngine.unload()
                withContext(Dispatchers.Main) { models.delete(f); installed.value = models.installed(); refreshStorage() }
            }
            return
        }
        models.delete(f)
        installed.value = models.installed()
        refreshStorage()
    }

    fun partialSize(url: String) = models.partialSize(url)

    // ── Kokoro TTS ────────────────────────────────────────────────────────────
    private var kokoroJob: Job? = null

    fun downloadKokoro() {
        if (kokoroJob?.isActive == true) return
        kokoroJob = kokoro.downloadAll(viewModelScope)
    }

    fun cancelKokoro() {
        kokoroJob?.cancel()
        kokoro.cancelDownload()
    }

    fun deleteKokoro() {
        kokoroJob?.cancel()
        kokoro.deleteAll()
    }

    fun selectKokoroVoice(v: com.astra.agent.llm.KokoroVoice) { kokoro.selectedVoice.value = v }
    fun setKokoroSpeed(s: Float) { kokoro.speed.value = s }
    fun setTtsEngine(e: com.astra.agent.llm.TtsEngine) { voice.ttsEngine.value = e }

    // ── Documents ─────────────────────────────────────────────────────────────
    /** Reads the file and shows it above the prompt box. It is only sent to the chat with the next message. */
    fun loadDocument(uri: Uri, displayName: String) {
        if (attached.value.size + docMgr.docs.value.size >= DocumentManager.MAX_DOCS) {
            add(InfoItem(uid(), "Maximum ${DocumentManager.MAX_DOCS} documents. Remove one first.", true)); return
        }
        viewModelScope.launch {
            readingDoc.value = displayName
            val result = docMgr.load(uri, displayName)
            readingDoc.value = null
            result.onSuccess { doc ->
                attached.update { l -> l.filter { it.name != doc.name } + doc }
                if (!doc.readable)
                    add(InfoItem(uid(), "\"${doc.name}\" has no selectable text (scanned PDF?). I can't read it.", true))
            }.onFailure { e ->
                add(InfoItem(uid(), "Failed to load document: ${e.message}", true))
            }
        }
    }

    fun removeAttached(name: String) { attached.update { l -> l.filter { it.name != name } } }
    fun removeDocument(name: String) { docMgr.remove(name) }

    private fun docBudget(s: Settings): Int =
        if (s.fastMode && s.openRouterKey.isNotBlank()) 30_000
        else (((s.nCtx - s.maxTokens - 700) * 3) / 2).coerceIn(1500, 24_000)

    // ── Chat ──────────────────────────────────────────────────────────────────
    fun newChat() {
        stop()
        saveSnapshot()
        resetState()
    }

    fun stop() {
        agentJob?.cancel()
        agentJob = null
        try { LlmEngine.stop() } catch (_: Throwable) {}
        try { voice.stopSpeaking(kokoro) } catch (_: Throwable) {}
        running.value = false
        liveStatus.value = ""
    }

    fun send(rawText: String) {
        val pending = attached.value
        val pendingImgs = attachedImages.value
        if (rawText.isBlank() && pending.isEmpty() && pendingImgs.isEmpty()) return
        if (running.value) {
            stop(); return
        }
        if (loadingModel.value) {
            add(InfoItem(uid(), "Model is loading. Please wait.", false)); return
        }

        // "Convert this PDF to Word" needs no AI at all: build the file straight from the document text.
        if (pendingImgs.isEmpty() && rawText.isNotBlank()) {
            val early = DocIntent.detect(rawText.trim(), pending, docMgr.docs.value)
            if (early != null && early.convertOnly && early.target != null) { convertDirect(rawText.trim(), early, pending); return }
        }

        val s = settings.value

        // Greetings, the time, the date and simple sums are answered instantly, with no model at all
        if (pending.isEmpty() && pendingImgs.isEmpty()) {
            val quick = com.astra.agent.agent.InstantAnswers.answer(rawText)
            if (quick != null) {
                voice.stopSpeaking()
                add(UserItem(uid(), rawText.trim()))
                add(AssistantItem(uid(), "", quick, false))
                history.add(Msg("user", rawText.trim())); history.add(Msg("assistant", quick))
                saveSnapshot()
                return
            }
        }

        // "remember that ...", "forget ...": handled on the spot, no model needed (works offline)
        if (pending.isEmpty() && pendingImgs.isEmpty()) {
            val sched = com.astra.agent.personal.ScheduleIntent.parse(rawText)
            if (sched != null) {
                voice.stopSpeaking()
                add(UserItem(uid(), rawText.trim()))
                val task = com.astra.agent.Alarms.store(app).add(sched.hour, sched.minute, sched.prompt)
                com.astra.agent.Alarms.arm(app, task)
                personalTick.value++
                add(AssistantItem(uid(), "", "Done \u2014 every day at ${task.timeLabel()} I'll send you a notification: \"${task.prompt}\". Tap it and I'll do it right then. " +
                    "You can see or delete scheduled tasks in Settings.", false))
                saveSnapshot()
                return
            }
            val cmd = MemoryIntent.detect(rawText)
            if (cmd != null) {
                voice.stopSpeaking()
                add(UserItem(uid(), rawText.trim()))
                val reply = when (cmd) {
                    is MemoryIntent.Cmd.Remember -> if (personal.addMemory(cmd.fact)) "Okay, I'll remember that." else "That is too short to remember."
                    is MemoryIntent.Cmd.Forget -> { val n = personal.forget(cmd.what); if (n > 0) "Done \u2014 I forgot it." else "I don't have anything like that saved." }
                    is MemoryIntent.Cmd.ForgetAll -> { personal.clearMemory(); "I've cleared everything I remembered about you." }
                }
                personalTick.value++
                add(AssistantItem(uid(), "", reply, false))
                saveSnapshot()
                return
            }
            val facts = MemoryIntent.auto(rawText)
            if (facts.isNotEmpty()) { facts.forEach { personal.addMemory(it) }; personalTick.value++ }
        }

        // "generate one apple image" / "make a short clip ..." goes straight to the image / video generator;
        // it needs no chat model at all, so it is checked before anything else.
        if (pending.isEmpty() && pendingImgs.isEmpty() && rawText.isNotBlank() &&
            DocIntent.detect(rawText.trim(), pending, docMgr.docs.value) == null) {
            val mediaReq = MediaIntent.detect(rawText.trim())
            if (mediaReq != null) {
                voice.stopSpeaking()
                add(UserItem(uid(), rawText.trim()))
                runMedia(mediaReq, s)
                return
            }
        }

        // Fast Mode: needs key but NOT a local model
        if (s.fastMode) {
            if (s.openRouterKey.isBlank()) {
                add(InfoItem(uid(), "Fast Mode is on but no OpenRouter API key is set. Go to Settings and add your key.", true))
                return
            }
        } else {
            // Local mode: needs a model
            if (loadedModel.value == null) {
                add(InfoItem(uid(), "No model loaded. Open the Models tab, download one and tap Load. Or enable Fast Mode in Settings.", true))
                return
            }
        }

        val text = rawText.ifBlank { if (pendingImgs.isNotEmpty() && pending.isEmpty()) "Describe this image." else "Summarize the attached document." }

        voice.stopSpeaking()
        val docReq = if (pendingImgs.isEmpty()) DocIntent.detect(text, pending, docMgr.docs.value) else DocIntent.detectWithImages(text)
        // Pictures that belong in a file (ppt, pdf, Word, Excel, website) are inserted by the app itself, so this works with any
        // model, local or cloud, online or offline. Only "look at this picture" questions need a cloud vision model.
        val imageForFile = pendingImgs.isNotEmpty() && (docReq != null || SiteIntent.detect(text))
        if (pendingImgs.isNotEmpty() && !imageForFile && !(s.fastMode && s.openRouterKey.isNotBlank())) {
            add(InfoItem(uid(), "Questions about a picture need Fast Mode (a cloud vision model). Turn on \u26a1 Fast and add your OpenRouter key. " +
                "To put the picture into a file, say for example \"add this image to a ppt about ...\".", true))
            return
        }
        val docImages: List<DocImage> = if (imageForFile) pendingImgs.mapNotNull {
            try { DocImage.of(it.name, android.util.Base64.decode(it.b64, android.util.Base64.NO_WRAP)) } catch (_: Throwable) { null }
        } else emptyList()
        currentDocImages = docImages
        val modelText = text + (if (docImages.isNotEmpty()) DocIntent.imageNote(docImages) else "")
        val modelImages = if (imageForFile && !(s.fastMode && s.openRouterKey.isNotBlank())) emptyList() else pendingImgs.map { it.b64 }
        // A file too big to edit in one go is edited part by part into a new file (see runBigEdit)
        val bigEdit = docReq != null && docReq.edit && docReq.target != null && docReq.target.text.length > editLimit(s)
        val intent = if (docReq != null) ReqIntent.DOC
                     else if (pending.isEmpty() && SiteIntent.detect(text)) ReqIntent.SITE
                     else CodeIntent.classify(text)
        currentIntent = intent
        currentDocReq = docReq
        lastRequest = text
        // Attached files move into the conversation now (not before)
        pending.forEach { docMgr.add(it) }
        attached.value = emptyList()
        attachedImages.value = emptyList()
        add(UserItem(uid(), text,
            pending.map { AttachInfo(it.name, it.pageCount, it.sizeKb) } +
            pendingImgs.map { AttachInfo(it.name, 0, 0, true, it.thumb) }))

        if (bigEdit && docReq != null) { runBigEdit(docReq, text, s); return }
        if (docReq == null && pending.isNotEmpty() && pendingImgs.isEmpty() && BigWork.isSummaryRequest(text) &&
            pending.all { it.readable } && pending.sumOf { it.text.length } > docBudget(s) * 6 / 5) {
            runBigSummary(pending, text, s); return
        }

        if (history.size > 40) {
            history.subList(0, history.size - 40).clear()
        }
        // New attachments: their text goes into this very message, and the earlier chat is not shown to the model,
        // so "summarize this" is about the new file and not about the previous answer.
        val inlineDocs = if (pending.isNotEmpty() && !(docReq != null && docReq.edit)) pending else emptyList()
        val inlineText = if (inlineDocs.isNotEmpty()) docMgr.inlineBlock(inlineDocs, text, docBudget(s)) else ""
        val userMsg: Msg
        if (inlineText.isNotEmpty()) {
            history.clear()
            userMsg = Msg("user", inlineText + "REQUEST ABOUT THE ATTACHED FILE(S) ABOVE: " + modelText +
                "\n(Answer from the attached file text above. Do not answer from earlier messages.)", modelImages)
        } else userMsg = Msg("user", modelText, modelImages)
        history.add(userMsg)
        val inlineNames = inlineDocs.map { it.name }.toSet()

        running.value = true
        liveStatus.value = when {
            intent == ReqIntent.SITE -> "Designing your website\u2026"
            intent == ReqIntent.APP  -> "Planning your app\u2026"
            intent == ReqIntent.CODE -> "Writing code\u2026"
            intent == ReqIntent.DOC  -> if (docReq?.edit == true) "Editing your document\u2026" else "Writing your document\u2026"
            s.fastMode               -> "Fast Mode \u26a1 Connecting\u2026"
            else                     -> "Thinking\u2026"
        }
        AgentService.start(app, "ZENIQ is working\u2026")

        agentJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val docCtx = try {
                    if (intent == ReqIntent.DOC && docReq != null) buildDocCtx(docReq)
                    else docMgr.buildContext(text, docBudget(settings.value), inlineNames)
                } catch (_: Exception) { "" }
                agent.run(history, effective(), ui, docCtx, intent)
            } catch (e: CancellationException) {
                add(InfoItem(uid(), "Stopped.", false))
            } catch (e: OutOfMemoryError) {
                add(InfoItem(uid(), "Out of memory. Try a smaller model or restart the app.", true))
            } catch (e: Throwable) {
                add(InfoItem(uid(), "Error: ${e.message?.take(200) ?: "Unknown error"}", true))
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    // keep the chat history small: the file text stays available through the document context
                    val at = history.indexOfFirst { it === userMsg }
                    if (at >= 0 && inlineText.isNotEmpty()) history[at] = Msg("user", text)
                    items.update { l -> l.map { if (it is AssistantItem && it.streaming) it.copy(streaming = false) else it } }
                    running.value = false
                    liveStatus.value = ""
                    AgentService.stop(app)
                    maybeGenerateTitle()
                }
            }
        }
    }

    // ── Big files: handled part by part ───────────────────────────────────────
    /** Edits a file that is too long for one prompt: the instruction is applied to every part, the result becomes a new file. */
    private fun runBigEdit(req: DocRequest, instruction: String, s: Settings) {
        val target = req.target ?: return
        running.value = true
        liveStatus.value = "Editing a large file in parts\u2026"
        AgentService.start(app, "ZENIQ is editing a large file\u2026")
        agentJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                add(InfoItem(uid(), "\"${target.name}\" is large (${target.text.length / 1000}k characters), so I edit it part by part and save the result as a new file.", false))
                val md = BigWork.rewrite(s, target.text, instruction) { i, n -> liveStatus.value = "Editing part $i of $n\u2026" }
                val made = createFiles(md, req.formats, target, "-edited", instruction)
                if (made == 0) add(InfoItem(uid(), "Couldn't create the edited file.", true))
            } catch (e: CancellationException) {
                add(InfoItem(uid(), "Stopped.", false))
            } catch (e: Throwable) {
                add(InfoItem(uid(), "Couldn't edit the file: ${e.message?.take(220) ?: "unknown error"}", true))
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { running.value = false; liveStatus.value = ""; AgentService.stop(app); saveSnapshot() }
            }
        }
    }

    /** Summary or overview of files too long for one prompt: notes for every part, then one combined answer. */
    private fun runBigSummary(docs: List<LoadedDoc>, request: String, s: Settings) {
        running.value = true
        liveStatus.value = "Reading a large file\u2026"
        AgentService.start(app, "ZENIQ is reading a large file\u2026")
        agentJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val text = docs.joinToString("\n\n") { "## ${it.name}\n${it.text}" }
                val answer = BigWork.summarize(s, text, request) { i, n -> liveStatus.value = "Reading part $i of $n\u2026" }
                add(AssistantItem(uid(), "", answer, false))
                history.add(Msg("user", request)); history.add(Msg("assistant", answer))
            } catch (e: CancellationException) {
                add(InfoItem(uid(), "Stopped.", false))
            } catch (e: Throwable) {
                add(InfoItem(uid(), "Couldn't read the file: ${e.message?.take(220) ?: "unknown error"}", true))
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { running.value = false; liveStatus.value = ""; AgentService.stop(app); saveSnapshot() }
            }
        }
    }

    // ── Images and videos ─────────────────────────────────────────────────────
    val cloudModels = MutableStateFlow(MediaGen.defaultModels)
    val cloudStatus = MutableStateFlow("")

    fun refreshCloudModels() {
        viewModelScope.launch {
            cloudStatus.value = "Loading models\u2026"
            val list = MediaGen.fetchAll()
            if (list.isEmpty()) cloudStatus.value = "Couldn't reach OpenRouter \u2014 showing the default list."
            else { cloudModels.value = list; cloudStatus.value = "${list.size} cloud models" }
        }
    }

    fun setImageModel(id: String) = updateSettings(settings.value.copy(imageModel = id))
    fun setVideoModel(id: String) = updateSettings(settings.value.copy(videoModel = id))
    /** A paid or free OpenRouter chat model (for example GPT): turns Fast Mode on and uses it. */
    fun useCloudChatModel(id: String) {
        updateSettings(settings.value.copy(openRouterModel = id, fastMode = true))
        if (settings.value.openRouterKey.isBlank()) add(InfoItem(uid(), "Connect OpenRouter in Settings > Connect provider to use $id.", false))
    }

    private fun runMedia(req: MediaIntent.Req, s: Settings) {
        val isImage = req.type == MediaIntent.Type.IMAGE
        running.value = true
        liveStatus.value = if (isImage) "Creating your image\u2026" else "Creating your video\u2026"
        AgentService.start(app, if (isImage) "ZENIQ is creating your image\u2026" else "ZENIQ is creating your video\u2026")
        agentJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val orKey = if (s.provider == Providers.OPENROUTER) s.openRouterKey else ""   // image and video models are OpenRouter models
                val res = if (isImage) MediaGen.image(req.prompt, s.imageModel, orKey)
                          else MediaGen.video(req.prompt, s.videoModel, orKey) { st -> liveStatus.value = st }
                val base = (if (isImage) "image-" else "video-") + DocBuilder.fileBase(req.prompt.take(40).ifBlank { "zeniq" })
                val saved = FileStorage.save(app, base, res.ext, res.mime, res.bytes)
                add(FileItem(uid(), saved.name, saved.path, saved.publicPath, saved.mime, if (isImage) "Image" else "Video", saved.sizeKb))
                add(InfoItem(uid(), (if (isImage) "Your image is ready." else "Your video is ready.") + " Saved to ${saved.publicPath}", false))
            } catch (e: CancellationException) {
                add(InfoItem(uid(), "Stopped.", false))
            } catch (e: java.net.UnknownHostException) {
                add(InfoItem(uid(), "No internet connection. Making images and videos needs internet.", true))
            } catch (e: Throwable) {
                add(InfoItem(uid(), "Couldn't make it: ${e.message?.take(240) ?: "unknown error"}", true))
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    running.value = false
                    liveStatus.value = ""
                    AgentService.stop(app)
                    saveSnapshot()
                }
            }
        }
    }

    // ── Websites (one self-contained HTML file) ───────────────────────────────
    /** Turns the model's short answer into a complete, designed website and shows it as a card with a Preview button. */
    @Volatile private var currentDocImages: List<DocImage> = emptyList()

    private fun buildSite(answer: String, request: String) {
        val pics = currentDocImages
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val spec = SiteContent.build(request, answer)
                val premium = StyleDetect.tier(request) == Tier.PREMIUM
                val prefs = app.getSharedPreferences("astra_doc_style", android.content.Context.MODE_PRIVATE)
                val key = if (premium) "site_last_premium" else "site_last_normal"
                val design = SiteDesigns.pick(premium, request, prefs.getString(key, null))
                prefs.edit().putString(key, design.id).apply()
                val html = SiteRenderer.render(spec, design, pics)
                val saved = FileStorage.save(app, "site-" + DocBuilder.fileBase(spec.name), "html", "text/html", html.toByteArray(Charsets.UTF_8))
                items.update { l -> l.filterNot { it is AssistantItem && it.text.isBlank() && it.thinking.isBlank() } }
                add(FileItem(uid(), saved.name, saved.path, saved.publicPath, saved.mime, "Website", saved.sizeKb))
                add(InfoItem(uid(), "Your ${if (premium) "premium " else ""}website \"${spec.name}\" is ready (${design.name} design, one HTML file). Tap Preview to see it.", false))
                saveSnapshot()
            } catch (e: Throwable) {
                add(InfoItem(uid(), "Couldn't build the website: ${e.message ?: "unknown error"}", true))
            }
        }
    }

    // ── Generated apps (ZIP) ──────────────────────────────────────────────────
    /** Reads the files out of the model's answer, zips them and shows a download card in the chat. */
    private fun packageApp(answer: String, request: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val project = FileExtractor.parseProject(answer, request)
                if (project.files.isEmpty()) {
                    add(InfoItem(uid(), "I couldn't find any code files in that answer, so no ZIP was made. Try again with a bit more detail.", true))
                    return@launch
                }
                val saved = AppStorage.save(app, project)
                add(AppItem(uid(), saved.name, saved.zipPath, saved.publicPath, saved.files, saved.sizeKb))
                saveSnapshot()
            } catch (e: Throwable) {
                add(InfoItem(uid(), "Couldn't create the ZIP: ${e.message ?: "unknown error"}", true))
            }
        }
    }

    /** Saves another copy of the ZIP into Documents/ZENIQApps. */
    fun downloadApp(item: AppItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val f = File(item.zipPath)
            val where = if (f.exists()) AppStorage.exportToDevice(app, f) else null
            if (where != null) add(InfoItem(uid(), "Saved ${f.name} to $where", false))
            else add(InfoItem(uid(), "Couldn't save the ZIP. Allow storage access for ZENIQ, or use Share.", true))
        }
    }

    // ── Documents: PDF / Word / Excel / PowerPoint ───────────────────────────
    /** Biggest document (in characters) that can be edited in one go with the current setup. */
    private fun editLimit(s: Settings): Int {
        if (s.fastMode && s.openRouterKey.isNotBlank()) return 18_000
        val room = (s.nCtx * 55 / 100).coerceIn(1024, 4096)
        return (minOf(room, s.nCtx - room - 700) * 3).coerceAtLeast(0)
    }

    private fun buildDocCtx(req: DocRequest): String {
        val sb = StringBuilder("\n\n")
        sb.append(DocIntent.formatHint(req.formats))
        if (req.edit && req.target != null) sb.append(DocIntent.editBlock(req.target, req.formats))
        return sb.toString()
    }

    /** Turns the model's Markdown answer into real files and shows download cards. */
    private fun packageDocs(answer: String, request: String, req: DocRequest?) {
        val formats = req?.formats?.takeIf { it.isNotEmpty() } ?: listOf(DocFormat.PDF)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val md = DocBuilder.clean(answer)
                if (md.isBlank() || MdParser.parse(md).isEmpty()) {
                    add(InfoItem(uid(), "I couldn't find any document content in that answer, so no file was made. Try again with a bit more detail.", true))
                    return@launch
                }
                createFiles(md, formats, req?.target, if (req?.edit == true) "-edited" else "", request)
                saveSnapshot()
            } catch (e: Throwable) {
                add(InfoItem(uid(), "Couldn't create the file: ${e.message ?: "unknown error"}", true))
            }
        }
    }

    /**
     * Picks the look of the new file. "premium / beautiful / professional" in the request selects the premium tier,
     * anything else the normal tier. The last template used is remembered so the next file looks different.
     */
    private fun chooseStyle(request: String): StyleChoice {
        val tier = StyleDetect.tier(request)
        val prefs = app.getSharedPreferences("astra_doc_style", android.content.Context.MODE_PRIVATE)
        val key = if (tier == Tier.PREMIUM) "last_premium" else "last_normal"
        val last = prefs.getString(key, null)
        val theme = Themes.pick(tier, prefs.getString(key + "_theme", null))
        val tpl = StyleDetect.pptTemplate(tier, last)
        prefs.edit().putString(key, tpl.id).putString(key + "_theme", theme.id).apply()
        val assets = app.assets
        return StyleChoice(theme, tpl) { name -> try { assets.open(name).use { it.readBytes() } } catch (_: Exception) { null } }
    }

    /** Builds one file per format from [md], saves them and adds a card for each. Returns how many succeeded. */
    private fun createFiles(md: String, formats: List<DocFormat>, source: LoadedDoc?, suffix: String, request: String): Int {
        val blocks = MdParser.parse(md)
        val title = MdParser.title(blocks)
            ?: source?.name?.substringBeforeLast('.')
            ?: request.split(Regex("\\s+")).take(5).joinToString(" ")
        val base = if (source != null) DocBuilder.fileBase(source.name.substringBeforeLast('.').removeSuffix("-edited")) + suffix
                   else DocBuilder.fileBase(title)
        var made = 0
        var firstName: String? = null
        val style = chooseStyle(request)
        val pics = currentDocImages
        for (fmt in formats) {
            try {
                val bytes = DocBuilder.build(fmt, md, title, style, pics)
                val saved = FileStorage.save(app, base, fmt.ext, fmt.mime, bytes)
                add(FileItem(uid(), saved.name, saved.path, saved.publicPath, saved.mime, fmt.label, saved.sizeKb))
                if (firstName == null) firstName = saved.name
                made++
            } catch (e: Throwable) {
                add(InfoItem(uid(), "Couldn't create the ${fmt.label}: ${e.message ?: e.javaClass.simpleName}", true))
            }
        }
        if (firstName != null) {
            // keep the newest generated file editable: "change the title", "add a column" ...
            generatedDocNames.forEach { docMgr.remove(it) }
            generatedDocNames.clear()
            docMgr.add(LoadedDoc(Uri.EMPTY, firstName, md, (md.length / 3000).coerceAtLeast(1), (md.length / 1024).coerceAtLeast(1), true))
            generatedDocNames.add(firstName)
        }
        return made
    }

    /** Format conversion without the AI: the document text is already known, so just build the new file. */
    private fun convertDirect(text: String, req: DocRequest, pending: List<LoadedDoc>) {
        val target = req.target ?: return
        pending.forEach { docMgr.add(it) }
        attached.value = emptyList()
        add(UserItem(uid(), text, pending.map { AttachInfo(it.name, it.pageCount, it.sizeKb) }))
        if (history.size > 40) history.subList(0, history.size - 40).clear()
        history.add(Msg("user", text))
        running.value = true
        liveStatus.value = "Converting\u2026"
        viewModelScope.launch(Dispatchers.IO) {
            var made = 0
            try {
                made = createFiles(target.text, req.formats, target, "", text)
            } catch (e: Throwable) {
                add(InfoItem(uid(), "Couldn't convert the file: ${e.message ?: "unknown error"}", true))
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    history.add(Msg("assistant", if (made > 0) "I converted ${target.name} (${req.formats.joinToString { it.label }})." else "The conversion failed."))
                    running.value = false
                    liveStatus.value = ""
                    saveSnapshot()
                }
            }
        }
    }

    /** Saves another copy of a generated file into the phone's Downloads/ZENIQ folder. */
    fun downloadFile(item: FileItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val f = File(item.path)
            val where = if (f.exists()) FileStorage.export(app, f, item.mime, toDownloads = true) else null
            if (where != null) add(InfoItem(uid(), "Downloaded ${f.name} to $where", false))
            else add(InfoItem(uid(), "Couldn't save the file. Allow storage access for ZENIQ, or use Share.", true))
        }
    }

    // ── Voice ─────────────────────────────────────────────────────────────────
    fun startVoiceInput() {
        voice.startListening(
            onResult = { text -> viewModelScope.launch(Dispatchers.Main) { send(text) } },
            onError  = { viewModelScope.launch(Dispatchers.Main) {
                add(InfoItem(uid(), "Voice not recognised. Tap the mic and try again.", false))
            }}
        )
    }

    fun stopVoiceInput() { voice.stopListening() }

    // ── Agent UI callbacks ────────────────────────────────────────────────────
    private val ui = object : AgentUi {
        override fun onAssistantStart(): String {
            val isFast = settings.value.fastMode && settings.value.openRouterKey.isNotBlank()
            liveStatus.value = if (isFast) "Fast Mode \u26a1 Generating\u2026" else "Generating response\u2026"
            val id = uid()
            viewModelScope.launch(Dispatchers.Main) { add(AssistantItem(id)) }
            return id
        }
        override fun onStream(id: String, p: Parsed) {
            if (currentIntent == ReqIntent.SITE) {          // the words are collected quietly; the finished website appears as a card
                liveStatus.value = "Designing your website\u2026"
                return
            }
            upd(id) { (it as AssistantItem).copy(thinking = p.thinking, text = p.answer) }
            val isFast = settings.value.fastMode && settings.value.openRouterKey.isNotBlank()
            liveStatus.value = when {
                currentIntent == ReqIntent.APP && p.answer.isNotBlank()  -> "Building your app\u2026"
                currentIntent == ReqIntent.CODE && p.answer.isNotBlank() -> "Writing code\u2026"
                currentIntent == ReqIntent.DOC && p.answer.isNotBlank()  -> "Writing your document\u2026"
                p.thinking.isNotBlank() && p.answer.isBlank() -> "Thinking\u2026"
                p.answer.isNotBlank() && isFast               -> "Fast Mode \u26a1 Writing\u2026"
                p.answer.isNotBlank()                         -> "Writing response\u2026"
                else                                          -> "Generating\u2026"
            }
        }
        override fun onToolStart(name: String, args: String): String {
            val label = toolDisplayName(name)
            liveStatus.value = "▶ $label"
            val id = uid()
            viewModelScope.launch(Dispatchers.Main) { add(ToolItem(id, name, args)) }
            AgentService.update(app, label)
            return id
        }
        override suspend fun requestApproval(name: String, args: String): Boolean {
            val d = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                val lastTool = items.value.lastOrNull { it is ToolItem }?.id
                if (lastTool != null) upd(lastTool) { (it as ToolItem).copy(waiting = true) }
                approval.value = Approval(name, args, d)
            }
            val r = d.await()
            withContext(Dispatchers.Main) {
                items.update { l -> l.map { if (it is ToolItem && it.waiting) it.copy(waiting = false) else it } }
            }
            return r
        }
        override fun onToolEnd(id: String, result: String, ok: Boolean) {
            upd(id) { (it as ToolItem).copy(result = result.take(2000), ok = ok) }
            liveStatus.value = "Processing…"
        }
        override fun onInfo(text: String, error: Boolean) {
            viewModelScope.launch(Dispatchers.Main) { add(InfoItem(uid(), text, error)) }
        }
        override fun onFinalAnswer(text: String) {
            if (currentIntent == ReqIntent.SITE) { buildSite(text, lastRequest); return }
            if (currentIntent == ReqIntent.DOC) {
                packageDocs(text, lastRequest, currentDocReq)
                if (ttsOn.value && text.isNotBlank()) { try { voice.speak("Your file is ready.", kokoro) } catch (_: Exception) {} }
                return
            }
            if (currentIntent == ReqIntent.APP) packageApp(text, lastRequest)
            if (ttsOn.value && text.isNotBlank()) {
                // never read source code aloud
                val spoken = text.replace(Regex("```[\\s\\S]*?(```|$)"), " ").trim()
                if (spoken.isNotBlank()) { try { voice.speak(spoken, kokoro) } catch (_: Exception) {} }
            }
        }
    }

    private fun toolDisplayName(name: String) = when (name) {
        "open_app"       -> "Opening App"
        "open_url"       -> "Opening URL"
        "read_screen"    -> "Reading Screen"
        "tap_text"       -> "Tapping Element"
        "tap_xy"         -> "Tapping Point"
        "type_text"      -> "Typing Text"
        "swipe"          -> "Swiping"
        "press"          -> "Pressing Key"
        "browser_open"   -> "Opening Browser"
        "browser_text"   -> "Reading Page"
        "browser_click"  -> "Clicking Link"
        "browser_type"   -> "Typing in Browser"
        "browser_submit" -> "Submitting Form"
        "browser_eval"   -> "Running Script"
        "run_shell"      -> "Running Command"
        "http_get"       -> "Fetching URL"
        "read_file"      -> "Reading File"
        "write_file"     -> "Writing File"
        "list_files"     -> "Listing Files"
        "get_time"       -> "Getting Time"
        "take_photo"     -> "Taking Photo"
        "web_search"     -> "Searching the Web"
        "youtube_search" -> "Searching YouTube"
        "maps_search"    -> "Searching Maps"
        "call_number"    -> "Opening Dialer"
        "send_sms"       -> "Sending Message"
        "set_alarm"      -> "Setting Alarm"
        "set_timer"      -> "Setting Timer"
        "wait"           -> "Waiting…"
        else             -> name
    }

    override fun onCleared() {
        super.onCleared()
        try { voice.release() } catch (_: Throwable) {}
        try { kokoro.release() } catch (_: Throwable) {}
        try { LlmEngine.stop() } catch (_: Throwable) {}
    }
}
