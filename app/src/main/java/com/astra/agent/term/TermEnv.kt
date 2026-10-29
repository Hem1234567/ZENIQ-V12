package com.astra.agent.term

import android.content.Context
import android.net.ConnectivityManager
import java.io.File

/** The two Linux systems. ALPINE (musl) runs Claude Code. UBUNTU (glibc) runs OpenClaw, which needs Node 24.22.1+ that Alpine cannot provide. */
enum class Distro { ALPINE, UBUNTU }

/**
 * Paths and process launch lines for the embedded Linux environment.
 *
 * How it runs without root and without Termux: an Alpine Linux root file system lives in the app's private storage and is entered with
 * PRoot (shipped inside the APK as libproot.so / libproot-loader.so, so Android allows executing it). PRoot only *maps* the guest programs
 * into memory through its loader, which is what makes this work on current Android versions where executing downloaded files is blocked.
 */
object TermEnv {
    const val PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    fun base(ctx: Context): File = File(ctx.filesDir, "term").also { it.mkdirs() }
    fun rootfs(ctx: Context, d: Distro = Distro.ALPINE): File = File(base(ctx), if (d == Distro.UBUNTU) "rootfs-ubuntu" else "rootfs")
    fun tmp(ctx: Context): File = File(base(ctx), "tmp").also { it.mkdirs() }
    fun downloads(ctx: Context): File = File(base(ctx), "download").also { it.mkdirs() }
    fun logFile(ctx: Context): File = File(base(ctx), "setup.log")
    fun stateFile(ctx: Context): File = File(base(ctx), "state.properties")
    fun proot(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir, "libproot.so")
    fun loader(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir, "libproot-loader.so")

    /**
     * PRoot tracing modes. This PRoot build answers some emulated syscalls (getcwd, fchdir) with ENOSYS in some modes on some phones,
     * so setup tries them in order and stores the first one that works in state.properties ("prootMode").
     */
    val PROOT_MODES = listOf("noseccomp", "seccomp", "seccomp-new")

    fun prootMode(ctx: Context): String = try {
        val p = java.util.Properties(); stateFile(ctx).inputStream().use { p.load(it) }
        p.getProperty("prootMode")?.takeIf { it in PROOT_MODES } ?: "noseccomp"
    } catch (_: Exception) { "noseccomp" }

    /** Environment for the PRoot process itself (not for the guest). No secrets, no proxy or DNS overrides. */
    fun hostEnv(ctx: Context, mode: String = prootMode(ctx)): Array<String> {
        val l = arrayListOf(
            "PROOT_LOADER=" + loader(ctx).absolutePath,
            "PROOT_TMP_DIR=" + tmp(ctx).absolutePath,
            "PATH=/system/bin",
            "HOME=" + ctx.filesDir.absolutePath,
            "TMPDIR=" + tmp(ctx).absolutePath
        )
        when (mode) {
            "noseccomp" -> l += "PROOT_NO_SECCOMP=1"
            "seccomp-new" -> l += "PROOT_ASSUME_NEW_SECCOMP=1"
        }
        return l.toTypedArray()
    }

    private const val CWD_SHIM = "/usr/lib/zeniq-cwd.js"
    private val CWD_SHIM_JS = """// ZENIQ: some PRoot builds answer getcwd() with ENOSYS. Only in that case, fall back to the tracked directory / ${'$'}PWD.
const orig = process.cwd, oc = process.chdir;
let fallback = null;
process.cwd = function cwd() {
  try { return orig.call(process); }
  catch (e) { if (e && e.code === 'ENOSYS') return fallback || process.env.PWD || '/root'; throw e; }
};
process.chdir = function chdir(d) {
  const base = process.cwd();
  oc.call(process, d);
  try { orig.call(process); fallback = null; }
  catch (e) { if (e && e.code === 'ENOSYS') fallback = require('path').resolve(base, String(d)); else throw e; }
};
"""

    /** Writes the Node.js getcwd fallback into the guest (host side, no PRoot needed). Safe to call often. */
    fun ensureShim(ctx: Context, d: Distro = Distro.ALPINE) {
        try {
            val dir = File(rootfs(ctx, d), "usr/lib")
            val f = File(dir, "zeniq-cwd.js")
            if (dir.isDirectory && !(f.exists() && f.length() == CWD_SHIM_JS.length.toLong())) f.writeText(CWD_SHIM_JS)
        } catch (_: Throwable) {}
    }

    /** Guest environment is set explicitly through env -i so nothing from Android leaks in. */
    private fun guestEnv(cwd: String = "/root", d: Distro = Distro.ALPINE): List<String> = listOf(
        "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LOGNAME=root", "PATH=$PATH", "TERM=xterm-256color",
        "COLORTERM=truecolor", "LANG=C.UTF-8", "USE_BUILTIN_RIPGREP=0", "SHELL=" + (if (d == Distro.UBUNTU) "/bin/bash" else "/bin/sh"),
        "PWD=$cwd", "NODE_OPTIONS=--require=$CWD_SHIM"
    )

    fun interactiveShell(cwd: String = "/root", d: Distro = Distro.ALPINE): List<String> =
        guestEnv(cwd, d) + (if (d == Distro.UBUNTU) listOf("/bin/bash", "-l") else listOf("/bin/sh", "-l"))
    fun command(cmd: String, d: Distro = Distro.ALPINE): List<String> = guestEnv("/root", d) + listOf("/bin/sh", "-c", cmd)

    /** Full argv (argv[0] is the PRoot binary). [workspace] is bound at /workspace and becomes the working directory. */
    fun prootArgs(ctx: Context, workspace: File?, inner: List<String>, d: Distro = Distro.ALPINE): List<String> {
        ensureShim(ctx, d)
        val a = ArrayList<String>()
        a += proot(ctx).absolutePath
        a += listOf("-r", rootfs(ctx, d).absolutePath, "-0", "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "--link2symlink", "-p", "-L", "--tcsetsf2tcsets")
        if (workspace != null) {
            File(rootfs(ctx, d), "workspace").mkdirs()
            a += listOf("-b", workspace.absolutePath + ":/workspace", "-w", "/workspace")
        } else a += listOf("-w", "/root")
        a += inner
        return a
    }

    /** Android has no resolv.conf for guests: copy the DNS servers of the active network (never a hard-coded server). */
    fun writeResolvConf(ctx: Context, d: Distro = Distro.ALPINE) {
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
            val dns = cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.mapNotNull { it.hostAddress }?.filter { !it.contains('%') }.orEmpty()
            if (dns.isEmpty()) return
            val f = File(rootfs(ctx, d), "etc/resolv.conf")
            f.parentFile?.mkdirs()
            // Ubuntu ships resolv.conf as a link to a file that does not exist in this environment: replace the link with a real file
            try { if (java.nio.file.Files.isSymbolicLink(f.toPath())) f.delete() } catch (_: Throwable) {}
            f.writeText(dns.joinToString("\n") { "nameserver $it" } + "\n")
        } catch (_: Throwable) {}
    }

    fun hasBinary(ctx: Context, rel: String, d: Distro = Distro.ALPINE): Boolean =
        try { java.nio.file.Files.exists(File(rootfs(ctx, d), rel).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) } catch (_: Throwable) { false }

    /** Files a fresh Ubuntu base system lacks: a hosts file (so "localhost" resolves). Host side, no PRoot needed. */
    fun prepareUbuntu(ctx: Context) {
        try {
            val etc = File(rootfs(ctx, Distro.UBUNTU), "etc")
            etc.mkdirs()
            val hosts = File(etc, "hosts")
            if (!hosts.exists() || hosts.length() == 0L) hosts.writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        } catch (_: Throwable) {}
    }

    fun freeBytes(ctx: Context): Long = ctx.filesDir.usableSpace
}
