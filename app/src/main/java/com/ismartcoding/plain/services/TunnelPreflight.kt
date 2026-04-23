package com.ismartcoding.plain.services

import android.content.Context
import com.ismartcoding.plain.preferences.CloudflareTunnelHostnamePreference
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Runs a battery of network pre-flight checks BEFORE launching cloudflared so the
 * user can see in the log exactly what part of the chain fails (DNS / Cloudflare
 * edge reachability / their own web server / their domain DNS records).
 */
object TunnelPreflight {
    private const val TAG = "preflight"

    fun run(context: Context) {
        TunnelLogger.i(TAG, "----- pre-flight checks -----")
        checkDns("www.cloudflare.com")
        checkDns("api.cloudflare.com")
        checkDns("region1.v2.argotunnel.com")
        checkTcp("region1.v2.argotunnel.com", 7844)
        checkTcp("region2.v2.argotunnel.com", 7844)
        checkTcp("api.cloudflare.com", 443)

        val hostname = runBlocking { CloudflareTunnelHostnamePreference.getAsync(context).trim() }
        if (hostname.isEmpty()) {
            TunnelLogger.w(TAG, "No public hostname configured in app settings. The tunnel can still run, but you won't have a friendly URL to test.")
        } else {
            TunnelLogger.i(TAG, "Configured public hostname: $hostname")
            checkDns(hostname)
            checkHostnamePointsToCloudflare(hostname)
            checkPublicHttp(hostname)
        }

        // Local origin (where cloudflared will forward traffic to).
        checkLocalOrigin(8080)
        TunnelLogger.i(TAG, "----- pre-flight done -----")
    }

    private fun checkDns(host: String) {
        try {
            val t0 = System.currentTimeMillis()
            val addrs = InetAddress.getAllByName(host)
            val ms = System.currentTimeMillis() - t0
            TunnelLogger.i(TAG, "DNS  $host -> ${addrs.joinToString { it.hostAddress ?: "?" }}  (${ms}ms)")
        } catch (t: Throwable) {
            TunnelLogger.e(TAG, "DNS  $host FAILED: ${t.message}. Phone has no DNS / no internet, or DNS is blocked.")
        }
    }

    private fun checkTcp(host: String, port: Int) {
        Socket().use { s ->
            try {
                val t0 = System.currentTimeMillis()
                s.connect(InetSocketAddress(host, port), 5000)
                val ms = System.currentTimeMillis() - t0
                TunnelLogger.i(TAG, "TCP  $host:$port  CONNECT OK  (${ms}ms)")
            } catch (t: Throwable) {
                TunnelLogger.e(TAG, "TCP  $host:$port  FAILED: ${t.message}. Either the network blocks port $port (some carriers/firewalls block 7844) or there's no internet at all.")
            }
        }
    }

    private fun checkHostnamePointsToCloudflare(host: String) {
        try {
            val addrs = InetAddress.getAllByName(host).map { it.hostAddress ?: "" }
            val onCf = addrs.any { isCloudflareIp(it) }
            if (onCf) {
                TunnelLogger.i(TAG, "DOMAIN $host resolves to Cloudflare IPs ✓")
            } else {
                TunnelLogger.e(TAG, "DOMAIN $host does NOT resolve to Cloudflare. Got: $addrs. Either: (1) your nameservers at GoDaddy aren't pointing to Cloudflare yet, OR (2) you didn't add a Public Hostname for this subdomain inside the tunnel, OR (3) DNS is still propagating (wait up to 1h).")
            }
        } catch (t: Throwable) {
            TunnelLogger.e(TAG, "DOMAIN $host: DNS lookup failed: ${t.message}. The subdomain probably doesn't exist as a CNAME yet — add the Public Hostname inside your Cloudflare tunnel.")
        }
    }

    private fun checkPublicHttp(host: String) {
        try {
            val url = URL("https://$host/")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.instanceFollowRedirects = false
            c.requestMethod = "HEAD"
            val code = c.responseCode
            val server = c.getHeaderField("Server") ?: "?"
            val cfRay = c.getHeaderField("Cf-Ray") ?: "(none)"
            TunnelLogger.i(TAG, "HTTPS https://$host/  ->  $code  Server=$server  CF-Ray=$cfRay")
            when {
                cfRay != "(none)" && code == 530 -> TunnelLogger.e(TAG, "Cloudflare reached you BUT returned 530: tunnel is registered without an active connector OR Public Hostname rule missing/wrong.")
                cfRay != "(none)" && code in 520..529 -> TunnelLogger.e(TAG, "Cloudflare reached you BUT got $code from origin: cloudflared can't reach the local web server (wrong port? web server off?).")
                cfRay != "(none)" && code == 1033 -> TunnelLogger.e(TAG, "Argo tunnel error 1033: Public Hostname not configured for this subdomain.")
                cfRay == "(none)" -> TunnelLogger.w(TAG, "No Cf-Ray header — request may not be hitting Cloudflare at all (DNS not pointing there).")
            }
            c.disconnect()
        } catch (t: Throwable) {
            TunnelLogger.e(TAG, "HTTPS https://$host/  FAILED: ${t.message}")
        }
    }

    private fun checkLocalOrigin(port: Int) {
        try {
            val url = URL("http://127.0.0.1:$port/")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 2000
            c.readTimeout = 2000
            c.requestMethod = "HEAD"
            val code = c.responseCode
            TunnelLogger.i(TAG, "ORIGIN http://127.0.0.1:$port/  ->  $code  (this is what cloudflared forwards to)")
            c.disconnect()
        } catch (t: Throwable) {
            TunnelLogger.e(TAG, "ORIGIN http://127.0.0.1:$port/  FAILED: ${t.message}. Your local web server isn't running on this port — turn on the Web Console first, and confirm its HTTP port matches what your Cloudflare Public Hostname points to.")
        }
    }

    private fun isCloudflareIp(ip: String): Boolean {
        // Quick heuristic: Cloudflare's main /16s. Not exhaustive but catches the common cases.
        val cfPrefixes = listOf(
            "104.16.", "104.17.", "104.18.", "104.19.", "104.20.", "104.21.",
            "172.64.", "172.65.", "172.66.", "172.67.",
            "162.159.", "188.114.", "190.93.", "197.234.", "198.41.",
            "131.0.72.", "108.162.",
        )
        if (cfPrefixes.any { ip.startsWith(it) }) return true
        // IPv6: 2606:4700::/32
        if (ip.startsWith("2606:4700")) return true
        return false
    }
}
