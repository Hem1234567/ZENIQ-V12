package com.astra.agent.term

import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties
import kotlin.coroutines.coroutineContext

enum class Stage(val label: String) {
    NOT_INITIALIZED("Not set up yet"), CHECKING("Checking this phone"), PREPARING_STORAGE("Preparing storage"),
    DOWNLOADING("Downloading Linux base system"), EXTRACTING("Unpacking Linux base system"), SELF_TEST("Testing the Linux environment"),
    INSTALLING_PACKAGES("Installing Node.js and tools"), INSTALLING_CLAUDE("Installing Claude Code"), INSTALLING_OPENCLAW("Installing OpenClaw"), VERIFYING("Verifying the install"),
    READY("Ready"), ERROR("Needs attention")
}

data class SetupUi(
    val stage: Stage = Stage.NOT_INITIALIZED,
    val message: String = "Claude Code runs inside ZENIQ in its own Linux environment. First setup downloads about 250 MB and needs internet once.",
    val progress: Float = -1f,
    val error: String? = null,
    val failedAt: Stage? = null,
    val claudeVersion: String? = null,
    val busy: Boolean = false
)

/**
 * One-time (and repairable) installation of the embedded environment: Alpine root file system -> Node.js and tools -> official Claude Code CLI.
 * Every step records its result in state.properties, so an interrupted setup resumes and a valid install is never downloaded again.
 * The environment is only marked READY after `claude --version` really ran inside it.
 */
object TermSetup {
    private val _ui = MutableStateFlow(SetupUi())
    val ui = _ui.asStateFlow()
    /** Same kind of state for OpenClaw. Both tools share the Linux base system and Node.js; only one setup runs at a time. */
    private const val OC_INTRO = "OpenClaw needs Node.js 24.22.1 or newer. The small Alpine system used for Claude Code cannot provide it, so Open Claw runs in its own Ubuntu system with the official Node.js 24. First setup downloads about 350 MB and needs internet once."
    private val _oc = MutableStateFlow(SetupUi(message = OC_INTRO))
    val ocUi = _oc.asStateFlow()
    private var target: MutableStateFlow<SetupUi> = _ui
    private var job: Job? = null
    /** Setup keeps running when the user leaves the screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val MIN_FREE = 1_200_000_000L
    // Pinned on purpose. "latest-stable" (3.24) ships apk-tools 3 + Node 24, and under this PRoot build every apk
    // database/cache write and the exec of node fail with "Function not implemented" (ENOSYS).
    // 3.21 = apk-tools 2.14 + musl 1.2.5 + Node 22 LTS, the combination PRoot-on-Android is known to handle.
    private const val ALPINE_BRANCH = "v3.21"
    /**
     * Under this PRoot, "#!/usr/bin/env node" scripts fail ("env: can't execute 'node': Function not implemented") although running
     * node directly works. So scripts of globally installed npm packages (npm, npx, claude) get "#!/usr/bin/node" instead, and npm itself
     * is started as `node npm-cli.js`.
     */
    private const val SHEBANG_FIX = "for l in /usr/bin/*; do t=\$(readlink -f \"\$l\" 2>/dev/null); case \"\$t\" in /usr/lib/node_modules/*) " +
        "[ \"\$(head -c 2 \"\$t\" 2>/dev/null)\" = '#!' ] && sed -i '1s|^#!/usr/bin/env node|#!/usr/bin/node|' \"\$t\" 2>/dev/null;; esac; done; "
    /**
     * npm can exit 0 and install the package yet create no /usr/bin/claude (the launcher it links is produced by a
     * postinstall step, which may not run under PRoot). Find the real entry point and link it ourselves.
     */
    private val LINK_CLAUDE = (
        "NR=@@(node /usr/lib/node_modules/npm/bin/npm-cli.js root -g 2>/dev/null); " +
        "echo \"diag: npm root -g=@@NR prefix=@@(node /usr/lib/node_modules/npm/bin/npm-cli.js prefix -g 2>/dev/null)\"; " +
        "if [ ! -e /usr/bin/claude ] && [ ! -L /usr/bin/claude ]; then " +
        "D=\"@@NR /usr/lib/node_modules /usr/local/lib/node_modules /root\"; " +
        "T=@@(find @@D -maxdepth 9 -path '*/.npm' -prune -o -type f \\( -name claude.exe -o -name claude \\) -path '*@anthropic-ai*' -print 2>/dev/null | head -n 1); " +
        "[ -z \"@@T\" ] && T=@@(find @@D -maxdepth 9 -path '*/.npm' -prune -o -type f -name cli.js -path '*claude-code*' -print 2>/dev/null | head -n 1); " +
        "[ -z \"@@T\" ] && T=@@(find /usr/local/bin /usr/local/lib/node_modules/.bin /root/.npm-global/bin -name claude 2>/dev/null | head -n 1); " +
        "if [ -n \"@@T\" ]; then chmod 755 \"@@T\" 2>/dev/null; ln -sf \"@@T\" /usr/bin/claude; echo \"linked /usr/bin/claude -> @@T\"; " +
        "else echo 'diag: no claude entry point found'; find / -xdev -maxdepth 8 -path '*anthropic*' -not -path '*/.npm/*' 2>/dev/null | head -n 12; " +
        "ls -la @@NR 2>&1 | head -n 8; fi; fi; ").replace("@@", "$")
    private const val ALPINE_BASE = "https://dl-cdn.alpinelinux.org/alpine/$ALPINE_BRANCH/releases/aarch64/"

    // ── state file ──────────────────────────────────────────────────────────
    private fun props(ctx: Context) = Properties().also { p -> try { TermEnv.stateFile(ctx).inputStream().use { p.load(it) } } catch (_: Exception) {} }
    private fun save(ctx: Context, p: Properties) { try { TermEnv.stateFile(ctx).outputStream().use { p.store(it, "ZENIQ terminal state (no secrets)") } } catch (_: Exception) {} }
    private fun flag(ctx: Context, key: String) = props(ctx).getProperty(key) == "1"
    private fun setProp(ctx: Context, key: String, value: String?) { val p = props(ctx); if (value == null) p.remove(key) else p.setProperty(key, value); save(ctx, p) }

    // ── log (never contains credentials: only installer output) ─────────────
    fun log(ctx: Context, line: String) {
        try {
            val f = TermEnv.logFile(ctx)
            if (f.length() > 400_000) f.writeText(f.readText().takeLast(150_000))
            f.appendText(line.take(600) + "\n")
        } catch (_: Exception) {}
    }
    fun readLog(ctx: Context): String = try { TermEnv.logFile(ctx).readText().takeLast(24_000) } catch (_: Exception) { "(no log yet)" }

    private fun set(stage: Stage, msg: String, progress: Float = -1f) { target.value = target.value.copy(stage = stage, message = msg, progress = progress, error = null, failedAt = null, busy = true) }
    private fun fail(ctx: Context, at: Stage, msg: String) { log(ctx, "ERROR [$at] $msg"); target.value = target.value.copy(stage = Stage.ERROR, failedAt = at, error = msg, message = msg, progress = -1f, busy = false) }
    private fun failB(ctx: Context, at: Stage, msg: String): Boolean { fail(ctx, at, msg); return false }

    // ── public API ──────────────────────────────────────────────────────────
    /** Called when the terminal tab opens: shows READY only after the installed CLI answers, otherwise shows what is missing. */
    fun refresh(ctx: Context, scope: CoroutineScope) {
        if (job?.isActive == true || _ui.value.stage == Stage.READY) return
        val app = ctx.applicationContext
        val p = props(app)
        val complete = p.getProperty("claude") == "1" && p.getProperty("alpineBranch") == ALPINE_BRANCH && TermEnv.hasBinary(app, "bin/busybox") && TermEnv.hasBinary(app, "usr/bin/claude")
        if (!complete) {
            val interrupted = File(TermEnv.base(app), "rootfs.tmp").exists() || TermEnv.downloads(app).listFiles()?.any { it.name.endsWith(".part") } == true || p.getProperty("rootfs") == "1"
            _ui.value = SetupUi(Stage.NOT_INITIALIZED, if (interrupted) "A previous setup did not finish. Tap Resume to continue where it stopped." else SetupUi().message)
            return
        }
        job = scope.launch {
            _ui.value = SetupUi(Stage.VERIFYING, "Checking the installed environment…", busy = true)
            TermEnv.writeResolvConf(app)
            val v = claudeVersion(app)
            if (v != null) _ui.value = SetupUi(Stage.READY, "Claude Code $v is installed.", claudeVersion = v)
            else fail(app, Stage.VERIFYING, "The installed environment did not start. Tap Repair environment (your projects are not touched).")
        }
    }

    fun start(ctx: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            try { runSetup(app) }
            catch (e: CancellationException) { fail(app, _ui.value.stage, "Setup was cancelled. Tap Resume to continue."); throw e }
            catch (e: Exception) { fail(app, _ui.value.stage, e.message ?: e.javaClass.simpleName) }
        }
    }

    fun cancel() { job?.cancel() }

    /** Repair keeps the Linux base but reinstalls tools and Claude Code. [wipe] removes everything inside the environment (projects are stored elsewhere and are not touched). */
    fun repair(ctx: Context, wipe: Boolean, scope: CoroutineScope) {
        val app = ctx.applicationContext
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            if (wipe) { File(TermEnv.base(app), "rootfs.tmp").deleteRecursively(); TermEnv.rootfs(app).deleteRecursively(); TermEnv.stateFile(app).delete() }
            else { setProp(app, "packages", null); setProp(app, "claude", null); setProp(app, "claudeVersion", null) }
            TermEnv.downloads(app).listFiles()?.forEach { it.delete() }
            _ui.value = SetupUi()
            if (wipe) _oc.value = SetupUi(message = OC_INTRO)
            try { runSetup(app) }
            catch (e: CancellationException) { fail(app, _ui.value.stage, "Setup was cancelled. Tap Resume to continue."); throw e }
            catch (e: Exception) { fail(app, _ui.value.stage, e.message ?: e.javaClass.simpleName) }
        }
    }

    /** Removes Claude's saved sign-in (supported way: delete its credential files). Projects and installed tools stay. */
    fun resetSignIn(ctx: Context) {
        val root = TermEnv.rootfs(ctx)
        File(root, "root/.claude/.credentials.json").delete()
        File(root, "root/.claude.json").delete()
    }

    // ── setup steps ─────────────────────────────────────────────────────────
    /** Steps 1 to 5, shared by Claude Code and OpenClaw: checks, Linux base system, PRoot self test, Node.js. Returns false after recording the failure. */
    private suspend fun ensureBase(ctx: Context): Boolean {
        // 1. compatibility
        set(Stage.CHECKING, "Checking this phone…")
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) return failB(ctx, Stage.CHECKING, "This phone is not 64-bit ARM (${Build.SUPPORTED_ABIS.joinToString()}). The embedded Linux environment supports arm64 phones only.")
        if (!TermEnv.proot(ctx).exists() || !TermEnv.loader(ctx).exists()) return failB(ctx, Stage.CHECKING, "The PRoot runtime is missing from this build of the app. Reinstall the APK.")
        // 2. storage
        set(Stage.PREPARING_STORAGE, "Checking free storage…")
        val branchOk = props(ctx).getProperty("alpineBranch") == ALPINE_BRANCH
        if (flag(ctx, "rootfs") && !branchOk) log(ctx, "installed base system is not Alpine $ALPINE_BRANCH: it will be replaced (projects are not touched)")
        val needDownload = !flag(ctx, "rootfs") || !TermEnv.hasBinary(ctx, "bin/busybox") || !branchOk
        if (needDownload && TermEnv.freeBytes(ctx) < MIN_FREE)
            return failB(ctx, Stage.PREPARING_STORAGE, "Not enough free storage: about 1.2 GB is needed, ${TermEnv.freeBytes(ctx) / 1_000_000} MB is free. Free some space and tap Retry.")
        // 3. root file system
        if (needDownload) {
            val net = TermNet.report(ctx, "dl-cdn.alpinelinux.org")
            if (!net.ok) return failB(ctx, Stage.DOWNLOADING, net.detail)
            setProp(ctx, "rootfs", null); setProp(ctx, "packages", null); setProp(ctx, "claude", null); setProp(ctx, "claudeVersion", null); setProp(ctx, "openclaw", null)
            set(Stage.DOWNLOADING, "Finding the current Linux base system…")
            val (file, sha) = resolveRootfs(ctx)
            val tarball = File(TermEnv.downloads(ctx), file)
            download(ctx, ALPINE_BASE + file, tarball, sha) { done, total ->
                target.value = target.value.copy(stage = Stage.DOWNLOADING, message = "Downloading Linux base system… ${done / 1024 / 1024} MB", progress = if (total > 0) done.toFloat() / total else -1f)
            }
            set(Stage.EXTRACTING, "Unpacking Linux base system…")
            val tmp = File(TermEnv.base(ctx), "rootfs.tmp")
            tmp.deleteRecursively()
            withContext(Dispatchers.IO) { extractTarGz(tarball, tmp) }
            TermEnv.rootfs(ctx).deleteRecursively()
            if (!tmp.renameTo(TermEnv.rootfs(ctx))) throw IOException("Could not move the unpacked system into place.")
            tarball.delete()
            setProp(ctx, "rootfs", "1"); setProp(ctx, "alpine", file); setProp(ctx, "alpineBranch", ALPINE_BRANCH)
        }
        // 4. self test (proves PRoot works on this phone before anything else is installed)
        set(Stage.SELF_TEST, "Testing the Linux environment…")
        TermEnv.writeResolvConf(ctx)
        val test = StringBuilder()
        val code = runInRoot(ctx, "echo zeniq-ok; uname -m", 60_000) { test.appendLine(it); log(ctx, it) }
        if (code != 0 || !test.contains("zeniq-ok"))
            return failB(ctx, Stage.SELF_TEST, "The Linux environment would not start on this phone (exit $code). Last output: ${test.toString().trim().takeLast(400)}. Tap View logs for details.")
        // 4b. PRoot mode: pick the tracing mode in which getcwd() works (this PRoot answers it with ENOSYS in some modes)
        if (props(ctx).getProperty("prootMode") == null) chooseProotMode(ctx)
        // 5. packages. Success means `node --version` really ran, not that a file named node exists.
        if (!flag(ctx, "packages") || !TermEnv.hasBinary(ctx, "usr/bin/node") || nodeVersion(ctx) == null) {
            val net = TermNet.report(ctx, "dl-cdn.alpinelinux.org")
            if (!net.ok) return failB(ctx, Stage.INSTALLING_PACKAGES, net.detail)
            set(Stage.INSTALLING_PACKAGES, "Installing Node.js, git and tools…")
            // Force HTTP: Alpine HTTPS needs kernel features PRoot on Android doesn't emulate.
            // --no-scripts skips post-install hooks that call fchdir() (not emulated by PRoot).
            // apk may still exit non-zero when it cannot write its own database, so the exit code is ignored
            // and the result is judged by actually running node below.
            val cmd = "sed -i 's|https://|http://|g' /etc/apk/repositories 2>/dev/null; " +
                "apk update --allow-untrusted 2>&1; " +
                "apk add --no-cache --allow-untrusted --no-scripts " +
                "bash git curl nodejs npm ripgrep libgcc libstdc++ ca-certificates nano 2>&1; " +
                "exit 0"
            runInRoot(ctx, cmd, 30 * 60_000L) { log(ctx, it); target.value = target.value.copy(message = "Installing Node.js and tools…\n" + it.take(80)) }
            var nv = nodeVersion(ctx)
            if (nv == null) { diagnoseNode(ctx); nv = applyNodeLoaderShim(ctx) }
            if (nv == null) return failB(ctx, Stage.INSTALLING_PACKAGES,
                "Node.js was installed but cannot run on this phone. Tap View logs (the 'diag:' lines say why) and send them.")
            log(ctx, "node $nv works")
            setProp(ctx, "packages", "1"); setProp(ctx, "nodeVersion", nv)
        }
        return true
    }

    private suspend fun runSetup(ctx: Context) {
        target = _ui
        log(ctx, "=== setup started ${java.util.Date()} ===")
        if (!ensureBase(ctx)) return
        // 6. Claude Code (official npm package; Alpine/arm64-musl is a supported platform)
        if (!flag(ctx, "claude") || !TermEnv.hasBinary(ctx, "usr/bin/claude")) {
            val net = TermNet.report(ctx, "registry.npmjs.org")
            if (!net.ok) return fail(ctx, Stage.INSTALLING_CLAUDE, net.detail)
            set(Stage.INSTALLING_CLAUDE, "Installing Claude Code (a few minutes)…")
            val npmOut = ArrayDeque<String>()
            val rc = runInRoot(ctx, SHEBANG_FIX + "NPM_CONFIG_UPDATE_NOTIFIER=false NPM_CONFIG_FUND=false NPM_CONFIG_AUDIT=false " +
                "node /usr/lib/node_modules/npm/bin/npm-cli.js install -g @anthropic-ai/claude-code 2>&1; rc=\$?; " + LINK_CLAUDE + SHEBANG_FIX + "exit \$rc", 40 * 60_000L) {
                log(ctx, it); npmOut.addLast(it); if (npmOut.size > 14) npmOut.removeFirst()
                target.value = target.value.copy(message = "Installing Claude Code…\n" + it.take(80))
            }
            if (rc != 0 || !TermEnv.hasBinary(ctx, "usr/bin/claude")) return fail(ctx, Stage.INSTALLING_CLAUDE,
                "Claude Code installation failed (exit $rc): ${npmOut.joinToString(" | ").takeLast(700)}. Tap Retry, or View logs.")
        }
        // 7. verify, only then READY
        set(Stage.VERIFYING, "Verifying Claude Code…")
        val v = claudeVersion(ctx) ?: return fail(ctx, Stage.VERIFYING, "Claude Code was installed but did not start. Tap Repair environment, or View logs.")
        setProp(ctx, "claude", "1"); setProp(ctx, "claudeVersion", v); setProp(ctx, "installedAt", System.currentTimeMillis().toString())
        log(ctx, "=== ready: Claude Code $v ===")
        _ui.value = SetupUi(Stage.READY, "Claude Code $v is installed.", claudeVersion = v)
    }

    // ── OpenClaw: its own Ubuntu (glibc) system with the official Node.js 24 ───────────────────────────────────
    // Why not Alpine like Claude Code: OpenClaw only starts on Node 24.22.1+ (or 26.12.1+) because older Node builds corrupt data in node:sqlite and
    // lack the current TLS root list. Alpine offers Node 22 only, and nodejs.org publishes no musl ARM64 build. The official glibc ARM64 build runs in Ubuntu.
    private const val UBUNTU_RELEASES = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
    private const val NODE24_DIST = "https://nodejs.org/dist/latest-v24.x/"
    private const val OC_MIN_FREE = 2_000_000_000L
    private const val OC_ENV = "SHARP_IGNORE_GLOBAL_LIBVIPS=1 NODE_LLAMA_CPP_SKIP_DOWNLOAD=true NPM_CONFIG_UPDATE_NOTIFIER=false NPM_CONFIG_FUND=false NPM_CONFIG_AUDIT=false "
    private val OC_PROPS = listOf("ubuntu", "ubuntuFile", "node24", "ocTools", "openclawU", "openclawVersion")

    /** After npm: make sure a launcher exists, that /usr/bin/node points at the new Node, and that the entry script starts Node by its full path. */
    private val LINK_OPENCLAW_U = (
        "NR=@@(node /usr/local/lib/node_modules/npm/bin/npm-cli.js root -g 2>/dev/null); " +
        "if [ ! -e /usr/local/bin/openclaw ] && [ ! -L /usr/local/bin/openclaw ]; then " +
        "T=@@(find @@NR /usr/local/lib/node_modules -maxdepth 3 -path '*/.npm' -prune -o -type f -name openclaw.mjs -print 2>/dev/null | head -n 1); " +
        "if [ -n \"@@T\" ]; then chmod 755 \"@@T\" 2>/dev/null; ln -sf \"@@T\" /usr/local/bin/openclaw; echo \"linked /usr/local/bin/openclaw -> @@T\"; " +
        "else echo 'diag: no openclaw entry point found'; ls -la @@NR 2>&1 | head -n 8; fi; fi; " +
        "ln -sf /usr/local/bin/node /usr/bin/node; " +
        "F=/usr/local/lib/node_modules/openclaw/openclaw.mjs; [ -f \"@@F\" ] && sed -i '1s|^#!/usr/bin/env node|#!/usr/local/bin/node|' \"@@F\"; "
    ).replace("@@", "$")

    /** git, curl and certificates from Ubuntu's own package archive (main only, to keep the download small). Best effort: OpenClaw installs without them. */
    private val UBUNTU_TOOLS_SCRIPT = (
        "export DEBIAN_FRONTEND=noninteractive; mkdir -p /etc/apt/apt.conf.d; " +
        "printf 'APT::Sandbox::User \"root\";\\nAcquire::Retries \"3\";\\nDpkg::Use-Pty \"0\";\\n' > /etc/apt/apt.conf.d/99zeniq; " +
        "C=@@(. /etc/os-release; echo @@VERSION_CODENAME); [ -n \"@@C\" ] || C=noble; " +
        "rm -f /etc/apt/sources.list.d/ubuntu.sources; K='[signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg]'; " +
        "printf 'deb %s http://ports.ubuntu.com/ubuntu-ports/ %s main\\ndeb %s http://ports.ubuntu.com/ubuntu-ports/ %s-updates main\\ndeb %s http://ports.ubuntu.com/ubuntu-ports/ %s-security main\\n' " +
        "\"@@K\" \"@@C\" \"@@K\" \"@@C\" \"@@K\" \"@@C\" > /etc/apt/sources.list; " +
        "apt-get update 2>&1 | tail -n 4; " +
        "apt-get install -y --no-install-recommends git ca-certificates curl 2>&1 | tail -n 6; exit 0"
    ).replace("@@", "$")

    private fun ocReady(ctx: Context) = props(ctx).getProperty("openclawU") == "1" &&
        TermEnv.hasBinary(ctx, "usr/local/bin/openclaw", Distro.UBUNTU) && TermEnv.hasBinary(ctx, "usr/local/bin/node", Distro.UBUNTU)

    /** Called when the Open Claw tab opens: shows READY only after the installed CLI answers, otherwise shows what is missing. */
    fun refreshOpenClaw(ctx: Context, scope: CoroutineScope) {
        if (job?.isActive == true || _oc.value.stage == Stage.READY) return
        val app = ctx.applicationContext
        val p = props(app)
        if (!ocReady(app)) {
            val interrupted = File(TermEnv.base(app), "rootfs-ubuntu.tmp").exists() || TermEnv.downloads(app).listFiles()?.any { it.name.endsWith(".part") } == true || p.getProperty("ubuntu") == "1"
            _oc.value = SetupUi(Stage.NOT_INITIALIZED, if (interrupted) "A previous setup did not finish. Tap Resume to continue where it stopped." else OC_INTRO)
            return
        }
        job = scope.launch {
            target = _oc
            _oc.value = SetupUi(Stage.VERIFYING, "Checking the installed environment…", busy = true)
            TermEnv.writeResolvConf(app, Distro.UBUNTU)
            TermEnv.prepareUbuntu(app)
            val v = openclawVersion(app)
            if (v != null) _oc.value = SetupUi(Stage.READY, "OpenClaw $v is installed.", claudeVersion = v)
            else fail(app, Stage.VERIFYING, "OpenClaw did not start. Tap Repair environment (your files are not touched).")
        }
    }

    fun startOpenClaw(ctx: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            try { runOpenClawSetup(app) }
            catch (e: CancellationException) { fail(app, _oc.value.stage, "Setup was cancelled. Tap Resume to continue."); throw e }
            catch (e: Exception) { fail(app, _oc.value.stage, e.message ?: e.javaClass.simpleName) }
        }
    }

    /** Repair reinstalls OpenClaw only. [wipe] also removes Open Claw's Ubuntu system. Claude Code and your projects are never touched. */
    fun repairOpenClaw(ctx: Context, wipe: Boolean, scope: CoroutineScope) {
        val app = ctx.applicationContext
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            if (wipe) {
                File(TermEnv.base(app), "rootfs-ubuntu.tmp").deleteRecursively()
                TermEnv.rootfs(app, Distro.UBUNTU).deleteRecursively()
                for (k in OC_PROPS) setProp(app, k, null)
            } else {
                setProp(app, "openclawU", null); setProp(app, "openclawVersion", null)
                if (TermEnv.hasBinary(app, "bin/sh", Distro.UBUNTU)) {
                    try { runInRoot(app, "rm -rf /usr/local/lib/node_modules/openclaw /usr/local/bin/openclaw; exit 0", 120_000L, null, null, Distro.UBUNTU) { } } catch (_: Exception) { }
                }
            }
            TermEnv.downloads(app).listFiles()?.forEach { it.delete() }
            _oc.value = SetupUi(message = OC_INTRO)
            try { runOpenClawSetup(app) }
            catch (e: CancellationException) { fail(app, _oc.value.stage, "Setup was cancelled. Tap Resume to continue."); throw e }
            catch (e: Exception) { fail(app, _oc.value.stage, e.message ?: e.javaClass.simpleName) }
        }
    }

    /** Removes OpenClaw's saved settings and keys (its folder in the Ubuntu home). The install and your files stay. */
    fun resetOpenClawConfig(ctx: Context) {
        File(TermEnv.rootfs(ctx, Distro.UBUNTU), "root/.openclaw").deleteRecursively()
    }

    /** Steps for Open Claw's own system: checks, Ubuntu base system, self test. Returns false after recording the failure. */
    private suspend fun ensureUbuntu(ctx: Context): Boolean {
        set(Stage.CHECKING, "Checking this phone…")
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) return failB(ctx, Stage.CHECKING, "This phone is not 64-bit ARM (${Build.SUPPORTED_ABIS.joinToString()}). The embedded Linux environment supports arm64 phones only.")
        if (!TermEnv.proot(ctx).exists() || !TermEnv.loader(ctx).exists()) return failB(ctx, Stage.CHECKING, "The PRoot runtime is missing from this build of the app. Reinstall the APK.")
        val needDownload = !flag(ctx, "ubuntu") || !TermEnv.hasBinary(ctx, "bin/bash", Distro.UBUNTU)
        set(Stage.PREPARING_STORAGE, "Checking free storage…")
        if (needDownload && TermEnv.freeBytes(ctx) < OC_MIN_FREE)
            return failB(ctx, Stage.PREPARING_STORAGE, "Not enough free storage: about 2 GB is needed, ${TermEnv.freeBytes(ctx) / 1_000_000} MB is free. Free some space and tap Retry.")
        if (needDownload) {
            val net = TermNet.report(ctx, "cdimage.ubuntu.com")
            if (!net.ok) return failB(ctx, Stage.DOWNLOADING, net.detail)
            for (k in OC_PROPS) setProp(ctx, k, null)
            set(Stage.DOWNLOADING, "Finding the current Ubuntu base system…")
            val pick = OcRuntime.pickUbuntu(httpText(UBUNTU_RELEASES + "SHA256SUMS"))
                ?: return failB(ctx, Stage.DOWNLOADING, "Could not find the Ubuntu base system for ARM64 in the official release list.")
            log(ctx, "ubuntu: ${pick.first} sha256=${pick.second}")
            val tarball = File(TermEnv.downloads(ctx), pick.first)
            download(ctx, UBUNTU_RELEASES + pick.first, tarball, pick.second) { done, total ->
                target.value = target.value.copy(stage = Stage.DOWNLOADING, message = "Downloading Ubuntu base system… ${done / 1024 / 1024} MB", progress = if (total > 0) done.toFloat() / total else -1f)
            }
            set(Stage.EXTRACTING, "Unpacking Ubuntu base system…")
            val tmp = File(TermEnv.base(ctx), "rootfs-ubuntu.tmp")
            tmp.deleteRecursively()
            withContext(Dispatchers.IO) { extractTarGz(tarball, tmp) }
            TermEnv.rootfs(ctx, Distro.UBUNTU).deleteRecursively()
            if (!tmp.renameTo(TermEnv.rootfs(ctx, Distro.UBUNTU))) throw IOException("Could not move the unpacked system into place.")
            tarball.delete()
            setProp(ctx, "ubuntu", "1"); setProp(ctx, "ubuntuFile", pick.first)
        }
        TermEnv.prepareUbuntu(ctx)
        set(Stage.SELF_TEST, "Testing the Ubuntu environment…")
        TermEnv.writeResolvConf(ctx, Distro.UBUNTU)
        val test = StringBuilder()
        val code = runInRoot(ctx, "echo zeniq-ok; uname -m", 60_000, null, null, Distro.UBUNTU) { test.appendLine(it); log(ctx, it) }
        if (code != 0 || !test.contains("zeniq-ok"))
            return failB(ctx, Stage.SELF_TEST, "The Ubuntu environment would not start on this phone (exit $code). Last output: ${test.toString().trim().takeLast(400)}. Tap View logs for details.")
        if (props(ctx).getProperty("prootMode") == null) chooseProotMode(ctx, Distro.UBUNTU)
        return true
    }

    /** `node --version` of the official Node.js inside the Ubuntu system, or null. Also proves node can read its working directory. */
    private suspend fun ubuntuNodeVersion(ctx: Context): String? {
        val out = StringBuilder()
        val rc = runInRoot(ctx, "/usr/local/bin/node --version 2>&1 && /usr/local/bin/node -e 'process.cwd()' 2>&1", 90_000, null, null, Distro.UBUNTU) { out.appendLine(it) }
        if (rc != 0) { log(ctx, "ubuntu node --version failed (exit $rc): " + out.toString().trim().takeLast(300)); return null }
        return Regex("""v\d+\.\d+\.\d+""").find(out)?.value
    }

    /** The official Node.js 24 (glibc, ARM64) from nodejs.org, checked against its published SHA-256, unpacked into /usr/local of the Ubuntu system. */
    private suspend fun ensureNode24(ctx: Context): Boolean {
        val have = if (TermEnv.hasBinary(ctx, "usr/local/bin/node", Distro.UBUNTU)) ubuntuNodeVersion(ctx) else null
        if (OcRuntime.nodeOk(have)) { setProp(ctx, "node24", have ?: ""); log(ctx, "node $have is already installed"); return true }
        val net = TermNet.report(ctx, "nodejs.org")
        if (!net.ok) return failB(ctx, Stage.DOWNLOADING, net.detail)
        set(Stage.DOWNLOADING, "Finding the current Node.js 24…")
        val pick = OcRuntime.pickNode(httpText(NODE24_DIST + "SHASUMS256.txt"))
            ?: return failB(ctx, Stage.DOWNLOADING, "No Node.js ${OcRuntime.NODE_MIN_24} or newer for ARM64 Linux was found on nodejs.org. Try again later.")
        log(ctx, "node: ${pick.first} sha256=${pick.second}")
        val tarball = File(TermEnv.downloads(ctx), pick.first)
        download(ctx, NODE24_DIST + pick.first, tarball, pick.second) { done, total ->
            target.value = target.value.copy(stage = Stage.DOWNLOADING, message = "Downloading Node.js 24… ${done / 1024 / 1024} MB", progress = if (total > 0) done.toFloat() / total else -1f)
        }
        set(Stage.EXTRACTING, "Unpacking Node.js 24…")
        withContext(Dispatchers.IO) { extractTarGz(tarball, File(TermEnv.rootfs(ctx, Distro.UBUNTU), "usr/local"), 1) }
        tarball.delete()
        val v = ubuntuNodeVersion(ctx)
        if (!OcRuntime.nodeOk(v)) {
            runInRoot(ctx, "ls -l /usr/local/bin/node; head -c 4 /usr/local/bin/node | od -c | head -1; exit 0", 30_000, null, null, Distro.UBUNTU) { log(ctx, "diag: $it") }
            return failB(ctx, Stage.INSTALLING_PACKAGES, "Node.js 24 was installed but cannot run on this phone (${v ?: "no version"}). Tap View logs (the 'diag:' lines say why) and send them.")
        }
        log(ctx, "node $v works")
        setProp(ctx, "node24", v ?: "")
        return true
    }

    /** git, curl and certificates (best effort: if the package archive cannot be reached, OpenClaw is installed without them). */
    private suspend fun ensureUbuntuTools(ctx: Context) {
        if (flag(ctx, "ocTools") && TermEnv.hasBinary(ctx, "usr/bin/git", Distro.UBUNTU)) return
        set(Stage.INSTALLING_PACKAGES, "Installing git and certificates…")
        val net = TermNet.report(ctx, "ports.ubuntu.com")
        if (!net.ok) { log(ctx, "skipping git install: ${net.detail}"); return }
        runInRoot(ctx, UBUNTU_TOOLS_SCRIPT, 30 * 60_000L, null, null, Distro.UBUNTU) { log(ctx, it); target.value = target.value.copy(message = "Installing git and certificates…\n" + it.take(80)) }
        if (TermEnv.hasBinary(ctx, "usr/bin/git", Distro.UBUNTU)) setProp(ctx, "ocTools", "1") else log(ctx, "git could not be installed; continuing without it")
    }

    private suspend fun runOpenClawSetup(ctx: Context) {
        target = _oc
        log(ctx, "=== OpenClaw setup started ${java.util.Date()} ===")
        if (!ensureUbuntu(ctx)) return
        if (!ensureNode24(ctx)) return
        ensureUbuntuTools(ctx)
        // the OpenClaw package
        if (!ocReady(ctx)) {
            val net = TermNet.report(ctx, "registry.npmjs.org")
            if (!net.ok) return fail(ctx, Stage.INSTALLING_OPENCLAW, net.detail)
            set(Stage.INSTALLING_OPENCLAW, "Installing OpenClaw (several minutes)…")
            val npmOut = ArrayDeque<String>()
            suspend fun install(extra: String): Int = runInRoot(ctx, OC_ENV +
                "node /usr/local/lib/node_modules/npm/bin/npm-cli.js install -g openclaw@latest $extra 2>&1; rc=\$?; " + LINK_OPENCLAW_U + "exit \$rc", 45 * 60_000L, null, null, Distro.UBUNTU) {
                log(ctx, it); npmOut.addLast(it); if (npmOut.size > 14) npmOut.removeFirst()
                target.value = target.value.copy(message = "Installing OpenClaw…\n" + it.take(80))
            }
            var rc = install("")
            if (rc != 0 || !TermEnv.hasBinary(ctx, "usr/local/bin/openclaw", Distro.UBUNTU)) {
                log(ctx, "first install attempt failed (exit $rc): trying again without install scripts")
                set(Stage.INSTALLING_OPENCLAW, "Installing OpenClaw (second try)…")
                rc = install("--ignore-scripts")
            }
            if (rc != 0 || !TermEnv.hasBinary(ctx, "usr/local/bin/openclaw", Distro.UBUNTU)) return fail(ctx, Stage.INSTALLING_OPENCLAW,
                "OpenClaw installation failed (exit $rc): ${npmOut.joinToString(" | ").takeLast(700)}. Tap Retry, or View logs.")
        }
        set(Stage.VERIFYING, "Verifying OpenClaw…")
        val v = openclawVersion(ctx) ?: return fail(ctx, Stage.VERIFYING, "OpenClaw was installed but did not start. Tap View logs (the lines show the reason), then Repair environment.")
        setProp(ctx, "openclawU", "1"); setProp(ctx, "openclawVersion", v)
        log(ctx, "=== ready: OpenClaw $v ===")
        _oc.value = SetupUi(Stage.READY, "OpenClaw $v is installed.", claudeVersion = v)
    }

    /** Runs `openclaw --version` inside the Ubuntu system. This also proves OpenClaw accepts the Node.js version. Returns the version or null. */
    suspend fun openclawVersion(ctx: Context): String? {
        val out = StringBuilder()
        val rc = runInRoot(ctx, "openclaw --version 2>&1", 120_000, null, null, Distro.UBUNTU) { out.appendLine(it) }
        if (rc != 0) { log(ctx, "openclaw --version failed: " + out.toString().takeLast(500)); return null }
        return OcRuntime.openclawVersion(out.toString())
    }

    private suspend fun chooseProotMode(ctx: Context, distro: Distro = Distro.ALPINE) {
        var chosen: String? = null
        for (m in TermEnv.PROOT_MODES) {
            val out = StringBuilder()
            val rc = runInRoot(ctx, "cd /root && /bin/pwd 2>&1; echo rc=\$?", 30_000, m, null, distro) { out.appendLine(it) }
            log(ctx, "proot mode $m: " + out.toString().trim().replace('\n', ' ') + " (exit $rc)")
            if (out.lines().any { it.trim() == "/root" }) { chosen = m; break }
        }
        if (chosen == null) log(ctx, "no PRoot mode answers getcwd(); relying on the Node.js fallback (zeniq-cwd.js)")
        else log(ctx, "using PRoot mode: $chosen")
        setProp(ctx, "prootMode", chosen ?: "noseccomp")
    }

    /** Runs `node --version` inside the environment. Returns "v22.x.y" or null. */
    private suspend fun nodeVersion(ctx: Context): String? {
        val out = StringBuilder()
        val rc = runInRoot(ctx, "node --version 2>&1 && node -e 'process.cwd()' 2>&1", 60_000) { out.appendLine(it) }
        if (rc != 0) { log(ctx, "node --version failed (exit $rc): " + out.toString().trim().takeLast(300)); return null }
        return Regex("""v\d+\.\d+\.\d+""").find(out)?.value
    }

    /** Writes facts about this phone and the installed node into the log, so a failure can be understood from "View logs". */
    private suspend fun diagnoseNode(ctx: Context) {
        log(ctx, "diag: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, abis=${Build.SUPPORTED_ABIS.joinToString()}")
        val cmd = "cat /etc/alpine-release; apk --version; ls -l /usr/bin/node /lib/ld-musl-aarch64.so.1; " +
            "head -c 4 /usr/bin/node | od -c | head -1; " +
            "/lib/ld-musl-aarch64.so.1 /usr/bin/node --version; exit 0"
        runInRoot(ctx, "$cmd 2>&1", 60_000) { log(ctx, "diag: $it") }
    }

    /**
     * Fallback if exec()ing node directly fails inside PRoot but running it through the musl dynamic loader works:
     * /usr/bin/node becomes a tiny launcher script. Returns node's version, or null if this did not help.
     */
    private suspend fun applyNodeLoaderShim(ctx: Context): String? {
        val rc = runInRoot(ctx, "/lib/ld-musl-aarch64.so.1 /usr/bin/node --version", 60_000) { }
        if (rc != 0) return null
        log(ctx, "direct exec of node fails but the musl loader can start it: installing a launcher script")
        runInRoot(ctx, "[ -f /usr/bin/node.real ] || mv /usr/bin/node /usr/bin/node.real; " +
            "printf '#!/bin/sh\\nexec /lib/ld-musl-aarch64.so.1 /usr/bin/node.real \"\$@\"\\n' > /usr/bin/node; chmod 755 /usr/bin/node; exit 0", 60_000) { log(ctx, it) }
        return nodeVersion(ctx)
    }

    /** Runs `claude --version` inside the environment. Returns the version or null. */
    suspend fun claudeVersion(ctx: Context): String? {
        val out = StringBuilder()
        val rc = runInRoot(ctx, "claude --version", 90_000) { out.appendLine(it) }
        if (rc != 0) { log(ctx, "claude --version failed: " + out.toString().takeLast(300)); return null }
        return Regex("""\d+\.\d+\.\d+""").find(out)?.value
    }

    /** Runs a shell command inside the Linux environment without a terminal, streaming output lines. Cancellable, with a timeout. */
    suspend fun runInRoot(ctx: Context, cmd: String, timeoutMs: Long, mode: String? = null, workspace: File? = null, distro: Distro = Distro.ALPINE, onLine: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(TermEnv.prootArgs(ctx, workspace, TermEnv.command(cmd, distro), distro)).redirectErrorStream(true)
        pb.environment().clear()
        for (kv in TermEnv.hostEnv(ctx, mode ?: TermEnv.prootMode(ctx))) { val i = kv.indexOf('='); pb.environment()[kv.substring(0, i)] = kv.substring(i + 1) }
        val p = pb.start()
        var timedOut = false
        val watchdog = launch { try { delay(timeoutMs); timedOut = true } finally { p.destroyForcibly() } }
        try {
            p.inputStream.bufferedReader().forEachLine { onLine(it) }
            val rc = p.waitFor()
            if (timedOut) { onLine("[timed out after ${timeoutMs / 1000}s]"); -1 } else rc
        } finally { watchdog.cancel(); p.destroyForcibly() }
    }

    // ── downloads ───────────────────────────────────────────────────────────
    private suspend fun httpText(url: String): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        var wait = 2000L
        repeat(4) { attempt ->
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                return@withContext c.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) { last = e; if (attempt < 3) { delay(wait); wait *= 2 } }
        }
        throw IOException("Could not read $url (${last?.message}).")
    }

    private suspend fun resolveRootfs(ctx: Context): Pair<String, String> {
        val yaml = httpText(ALPINE_BASE + "latest-releases.yaml")
        val blocks = yaml.split(Regex("(?m)^-\\s*$"))
        for (b in blocks) {
            val file = Regex("""(?:file|iso):\s*(alpine-minirootfs-[\w.\-]+-aarch64\.tar\.gz)""").find(b)?.groupValues?.get(1) ?: continue
            val sha = Regex("""sha256:\s*([0-9a-f]{64})""").find(b)?.groupValues?.get(1) ?: continue
            log(ctx, "rootfs: $file sha256=$sha")
            return file to sha
        }
        throw IOException("Could not find the Alpine Linux base system in the official release list.")
    }

    /** Resumable download with timeouts, exponential backoff (max 4 attempts) and SHA-256 verification. Never loops forever. */
    private suspend fun download(ctx: Context, url: String, dest: File, sha256: String, onProgress: (Long, Long) -> Unit) {
        if (dest.exists() && sha(dest) == sha256) return
        val part = File(dest.path + ".part")
        var wait = 3000L
        var lastError = "unknown"
        for (attempt in 1..4) {
            coroutineContext.ensureActive()
            try {
                withContext(Dispatchers.IO) {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 30000
                    val have = if (part.exists()) part.length() else 0L
                    if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                    val code = c.responseCode
                    if (code == 416) return@withContext   // already complete, verified below
                    if (code == 403 || code == 404) throw NoRetry("The server answered HTTP $code for the Linux base system.")
                    if (code != 200 && code != 206) throw IOException("HTTP $code")
                    val resume = code == 206 && have > 0
                    val total = c.contentLengthLong.let { if (it > 0) it + (if (resume) have else 0) else -1L }
                    var done = if (resume) have else 0L
                    java.io.FileOutputStream(part, resume).use { out ->
                        c.inputStream.use { ins ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = ins.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n; onProgress(done, total)
                            }
                        }
                    }
                }
                if (sha(part) == sha256) { part.renameTo(dest); return }
                part.delete(); lastError = "checksum mismatch (download was damaged)"
            } catch (e: NoRetry) { throw IOException(e.message) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { lastError = e.message ?: e.javaClass.simpleName }
            log(ctx, "download attempt $attempt failed: $lastError")
            if (attempt < 4) { target.value = target.value.copy(message = "Download interrupted ($lastError). Retrying in ${wait / 1000}s…"); delay(wait); wait *= 2 }
        }
        throw IOException("Download failed after 4 attempts: $lastError. Check your connection and tap Retry — it resumes where it stopped.")
    }
    private class NoRetry(msg: String) : Exception(msg)

    private fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { ins -> val buf = ByteArray(64 * 1024); while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ── tar.gz extraction (keeps symlinks, refuses paths that escape the target) ─────
    @Suppress("DEPRECATION")
    private fun extractTarGz(src: File, dest: File, strip: Int = 0) {
        dest.mkdirs()
        val root = dest.toPath().normalize()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(FileInputStream(src), 1 shl 16))).use { tin ->
            var e: TarArchiveEntry? = tin.nextTarEntry
            while (e != null) {
                var name = e.name.removePrefix("./").trimStart('/')
                repeat(strip) { name = if (name.contains('/')) name.substringAfter('/') else "" }   // drop the top folder (node-v24.x-linux-arm64/)
                val out = File(dest, name)
                if (name.isNotEmpty() && out.toPath().normalize().startsWith(root)) {
                    try {
                        when {
                            e.isDirectory -> { out.mkdirs(); try { Os.chmod(out.path, (e.mode and 0xFFF) or 0x1C0) } catch (_: Throwable) {} }
                            e.isSymbolicLink -> { out.parentFile?.mkdirs(); try { Os.remove(out.path) } catch (_: Throwable) {}; Os.symlink(e.linkName, out.path) }
                            e.isLink -> {
                                out.parentFile?.mkdirs()
                                val target = File(dest, e.linkName.removePrefix("./").trimStart('/'))
                                if (target.exists() && target.toPath().normalize().startsWith(root)) target.copyTo(out, overwrite = true)
                            }
                            e.isFile -> {
                                out.parentFile?.mkdirs()
                                out.outputStream().buffered().use { tin.copyTo(it) }
                                try { Os.chmod(out.path, (e.mode and 0xFFF) or 0x180) } catch (_: Throwable) {}
                            }
                            else -> {}   // device nodes and fifos are not needed (PRoot binds /dev)
                        }
                    } catch (ex: Exception) { throw IOException("Unpacking failed at $name: ${ex.message}") }
                }
                e = tin.nextTarEntry
            }
        }
    }
}
