package com.astra.agent.term

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class NetState { OK, OFFLINE, DNS_FAILED, TLS_FAILED, SERVICE_UNREACHABLE }
data class NetReport(val state: NetState, val detail: String) { val ok get() = state == NetState.OK }

/** Separates "phone is offline" from "this server is not reachable". Uses normal certificate checks; nothing is bypassed. */
object TermNet {
    suspend fun report(ctx: Context, host: String): NetReport = withContext(Dispatchers.IO) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            return@withContext NetReport(NetState.OFFLINE, "No internet connection. Connect to Wi-Fi or mobile data and retry.")
        try { InetAddress.getByName(host) }
        catch (_: UnknownHostException) { return@withContext NetReport(NetState.DNS_FAILED, "Connected, but the name $host could not be resolved (DNS problem on this network).") }
        try {
            val c = URL("https://$host/").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000; c.requestMethod = "HEAD"; c.instanceFollowRedirects = false
            val code = c.responseCode
            c.disconnect()
            NetReport(NetState.OK, "$host reachable over HTTPS (HTTP $code).")
        } catch (e: SSLException) {
            NetReport(NetState.TLS_FAILED, "Secure connection to $host failed (${e.message}). The certificate was not accepted; ZENIQ never bypasses this. A captive portal or proxy on this network is the usual cause.")
        } catch (e: Exception) {
            NetReport(NetState.SERVICE_UNREACHABLE, "Internet works, but $host did not answer (${e.javaClass.simpleName}). It may be down or blocked on this network.")
        }
    }
}
