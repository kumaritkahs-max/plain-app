package com.ismartcoding.plain.services

/**
 * Pattern-match cloudflared output lines and translate them to a plain-English
 * explanation of *why* the connection is failing. Returns null when the line is
 * routine.
 */
object TunnelDiagnostics {
    fun classify(line: String): String? {
        val l = line.lowercase()
        return when {
            // Auth / token problems
            l.contains("failed to unmarshal quick tunnel") ||
            l.contains("invalid tunnel secret") ||
            l.contains("token is invalid") ||
            l.contains("unauthorized") && l.contains("tunnel") ->
                "Tunnel token rejected by Cloudflare. The token you pasted is wrong, or the tunnel was deleted from your Cloudflare dashboard. Create a new tunnel and paste the fresh token."

            l.contains("error parsing tunnel id") ->
                "Tunnel token is malformed. Re-copy it from the Cloudflare dashboard (it's a long string starting with 'eyJ...')."

            // No public hostname configured
            l.contains("no ingress rules") ||
            l.contains("no rules") && l.contains("ingress") ->
                "Tunnel has no Public Hostname rule. In Cloudflare Zero Trust → Tunnels → your tunnel → Public Hostname tab, add: Subdomain + Domain → Service http://localhost:<your web console port>."

            // Connectivity to Cloudflare edge
            l.contains("connection refused") && l.contains("argotunnel") ->
                "Phone can reach DNS but Cloudflare's edge port 7844 is blocked here (some carriers/Wi-Fi block it). Try a different network, or your carrier's data."

            l.contains("dial tcp") && l.contains("i/o timeout") ->
                "Network timeout reaching Cloudflare edge. Phone has weak/no internet, or a firewall is blocking outbound 7844/443."

            l.contains("no such host") ->
                "DNS lookup failed. Phone has no working DNS — try toggling Wi-Fi/airplane mode."

            l.contains("tls handshake") && (l.contains("error") || l.contains("failed")) ->
                "TLS handshake to Cloudflare failed. Phone clock might be wrong, or a captive portal is intercepting the connection."

            // Origin (your local web server) problems
            l.contains("connection refused") && (l.contains("127.0.0.1") || l.contains("localhost")) ->
                "Tunnel is up, but cloudflared can't reach your local web server. The Web Console is OFF, or running on a different port than the one configured in your Cloudflare Public Hostname."

            l.contains("dial tcp 127.0.0.1") && l.contains("connect: connection refused") ->
                "cloudflared can't reach the local origin. Start the Web Console (it must be running for the tunnel to forward traffic)."

            l.contains("origin server") && l.contains("error") ->
                "Origin server responded with an error. Check the Web Console is running and the port matches."

            // Quic protocol fallback
            l.contains("falling back to http2") || l.contains("falling back to quic") ->
                "Tunnel switching transport (this is normal — cloudflared tries multiple protocols)."

            // Edge connection success markers (don't classify as error, just note)
            l.contains("registered tunnel connection") -> null

            // Generic fatal markers
            l.contains("fatal") -> "cloudflared reported a FATAL error (see line above)."
            l.contains("panic:") -> "cloudflared crashed (panic). Likely a bad binary or unsupported CPU."

            else -> null
        }
    }
}
