package org.dexpace.sdk.core.util

import org.dexpace.sdk.core.config.Configuration
import org.dexpace.sdk.core.http.auth.ChallengeHandler
import org.dexpace.sdk.core.instrumentation.ClientLogger
import java.net.InetSocketAddress
import java.net.URI
import java.util.regex.Pattern

/**
 * Runtime carrier for HTTP proxy configuration.
 *
 * Holds the proxy [Type] and [address], an optional list of [nonProxyHosts] glob patterns,
 * and either inline [username] / [password] credentials or a pluggable [challengeHandler] for
 * Digest proxy authentication. [nonProxyHosts] are compiled once at construction so per-request
 * [bypassesProxy] lookups don't re-compile.
 *
 * Instances are immutable. Construct directly for explicit configuration, or use
 * [fromConfiguration] to pull settings from JVM system properties and standard environment
 * variables (`HTTPS_PROXY`, `HTTP_PROXY`, `NO_PROXY`).
 *
 * When both [username] / [password] and [challengeHandler] are supplied, [challengeHandler]
 * wins — it is strictly more flexible (Digest support).
 */
class ProxyOptions @JvmOverloads constructor(
    val type: Type,
    val address: InetSocketAddress,
    val nonProxyHosts: List<String> = emptyList(),
    val username: String? = null,
    val password: String? = null,
    val challengeHandler: ChallengeHandler? = null,
) {
    // Pre-compiled at construction so per-request matches don't re-compile.
    private val nonProxyPatterns: List<Pattern> = nonProxyHosts.map { compileGlob(it) }

    /** True if [host] matches any of the configured `nonProxyHosts` patterns. */
    fun bypassesProxy(host: String): Boolean = nonProxyPatterns.any { it.matcher(host).matches() }

    /**
     * Proxy protocol. [HTTP] is a CONNECT-tunneling or forward HTTP proxy; [SOCKS4] / [SOCKS5] are
     * the corresponding SOCKS variants. Transport adapters dispatch on this value to pick the right
     * `java.net.Proxy.Type`.
     */
    enum class Type { HTTP, SOCKS4, SOCKS5 }

    companion object {
        private val logger = ClientLogger(ProxyOptions::class)

        // Splitters per source: env var `NO_PROXY` uses comma; system property `http.nonProxyHosts`
        // uses pipe. Each respects a preceding backslash as an escape so `"a\,b,c"` -> ["a,b", "c"].
        private val ENV_SPLIT: Pattern = Pattern.compile("(?<!\\\\),")
        private val PROP_SPLIT: Pattern = Pattern.compile("(?<!\\\\)\\|")

        /**
         * Reads proxy settings from [config] (which exposes system properties + env vars).
         * Returns null when no proxy is configured or when configuration is invalid. System
         * properties take precedence over env vars (JDK convention).
         *
         * Recognised keys:
         * - System property `https.proxyHost` / `https.proxyPort` / `https.proxyUser` /
         *   `https.proxyPassword` (preferred).
         * - System property `http.proxyHost` / `http.proxyPort` (fallback).
         * - System property `http.nonProxyHosts` (pipe-separated, backslash-escapable).
         * - Env var `HTTPS_PROXY` / `HTTP_PROXY` — full URL form
         *   `http://user:pass@proxy:8080`.
         * - Env var `NO_PROXY` — comma-separated, supports `*` wildcard or `"*"` to bypass
         *   the proxy for everything (returns null).
         */
        @JvmStatic
        fun fromConfiguration(config: Configuration): ProxyOptions? {
            // 1. System property layer first: prefer https.* then fall back to http.*.
            // Use getProperty (case-preserving) — not get(...) — because these keys live as
            // system properties only and the JVM convention is camelCase (`https.proxyHost`).
            val httpsHost = config.getProperty("https.proxyHost")
            val httpHost = config.getProperty("http.proxyHost")
            val sysHost = httpsHost ?: httpHost
            val sysPortRaw = when {
                httpsHost != null -> config.getProperty("https.proxyPort")
                httpHost != null -> config.getProperty("http.proxyPort")
                else -> null
            }
            val sysUser = config.getProperty("https.proxyUser")
            val sysPassword = config.getProperty("https.proxyPassword")

            // Resolve non-proxy host list with system property winning over env var.
            val nonProxyHosts = resolveNonProxyHosts(config)
            // NO_PROXY = "*" (or sysprop equivalent) → bypass everything; no proxy applies.
            if (nonProxyHosts == BYPASS_ALL) return null

            if (sysHost != null && sysHost.isNotEmpty()) {
                val port = parsePort(sysPortRaw) ?: return null
                return try {
                    ProxyOptions(
                        type = Type.HTTP,
                        address = InetSocketAddress(sysHost, port),
                        nonProxyHosts = nonProxyHosts,
                        username = sysUser,
                        password = sysPassword,
                    )
                } catch (t: IllegalArgumentException) {
                    logger.atWarning()
                        .event("proxy.config.invalid")
                        .field("host", sysHost)
                        .field("port", port)
                        .cause(t)
                        .log("Invalid proxy address; ignoring")
                    null
                }
            }

            // 2. Env var layer: HTTPS_PROXY then HTTP_PROXY.
            val envProxyUrl = config.get(Configuration.HTTPS_PROXY) ?: config.get(Configuration.HTTP_PROXY)
            if (envProxyUrl == null || envProxyUrl.isEmpty()) return null

            return parseProxyUrl(envProxyUrl, nonProxyHosts)
        }

        /**
         * Parses a full proxy URL (`http://user:pass@proxy:8080`) into a [ProxyOptions].
         * Returns null on any parse/validation failure (proxies are optional — never throw).
         */
        private fun parseProxyUrl(url: String, nonProxyHosts: List<String>): ProxyOptions? {
            val uri = try {
                URI(url)
            } catch (t: Exception) {
                logger.atWarning()
                    .event("proxy.config.invalid")
                    .field("url", url)
                    .cause(t)
                    .log("Could not parse proxy URL; ignoring")
                return null
            }

            val host = uri.host
            if (host == null || host.isEmpty()) {
                logger.atWarning()
                    .event("proxy.config.invalid")
                    .field("url", url)
                    .log("Proxy URL has no host; ignoring")
                return null
            }

            val rawPort = uri.port
            // URI.getPort returns -1 when absent. The user *must* specify a port — we don't
            // guess defaults (80/443) because the request's target scheme is unrelated to the
            // proxy's listen port.
            if (rawPort < 0 || rawPort > 65535) {
                logger.atWarning()
                    .event("proxy.config.invalid")
                    .field("url", url)
                    .field("port", rawPort)
                    .log("Proxy URL port out of range; ignoring")
                return null
            }

            val userinfo = uri.userInfo
            var user: String? = null
            var pass: String? = null
            if (userinfo != null && userinfo.isNotEmpty()) {
                val colon = userinfo.indexOf(':')
                if (colon >= 0) {
                    user = userinfo.substring(0, colon)
                    pass = userinfo.substring(colon + 1)
                } else {
                    user = userinfo
                }
            }

            return try {
                ProxyOptions(
                    type = Type.HTTP,
                    address = InetSocketAddress(host, rawPort),
                    nonProxyHosts = nonProxyHosts,
                    username = user,
                    password = pass,
                )
            } catch (t: IllegalArgumentException) {
                logger.atWarning()
                    .event("proxy.config.invalid")
                    .field("host", host)
                    .field("port", rawPort)
                    .cause(t)
                    .log("Invalid proxy address; ignoring")
                null
            }
        }

        /**
         * Resolves the effective non-proxy host list.
         *
         * System property `http.nonProxyHosts` (pipe-separated) wins over env var
         * `NO_PROXY` (comma-separated). Returns [BYPASS_ALL] when the configured value is
         * a single `*` — caller treats this as "no proxy applies to anything".
         */
        private fun resolveNonProxyHosts(config: Configuration): List<String> {
            val sysProp = config.getProperty("http.nonProxyHosts")
            if (sysProp != null && sysProp.isNotEmpty()) {
                val parts = splitAndUnescape(sysProp, PROP_SPLIT, '|')
                if (parts.size == 1 && parts[0] == "*") return BYPASS_ALL
                return parts
            }
            val envVar = config.get(Configuration.NO_PROXY)
            if (envVar != null && envVar.isNotEmpty()) {
                val parts = splitAndUnescape(envVar, ENV_SPLIT, ',')
                if (parts.size == 1 && parts[0] == "*") return BYPASS_ALL
                return parts
            }
            return emptyList()
        }

        /**
         * Split [raw] by [splitter] and strip the backslash escape character preceding the
         * literal [separator] so `"a\,b,c"` -> `["a,b", "c"]`. Empty fragments are dropped.
         */
        private fun splitAndUnescape(raw: String, splitter: Pattern, separator: Char): List<String> {
            val parts = splitter.split(raw)
            val out = ArrayList<String>(parts.size)
            for (p in parts) {
                if (p.isEmpty()) continue
                out.add(p.replace("\\" + separator, separator.toString()).trim())
            }
            return out
        }

        /** Strict port parser. Returns null when [raw] is missing, non-numeric, or out of range. */
        private fun parsePort(raw: String?): Int? {
            if (raw == null) return null
            val n = raw.toIntOrNull()
            if (n == null) {
                logger.atWarning()
                    .event("proxy.config.invalid")
                    .field("port", raw)
                    .log("Proxy port is not numeric; ignoring")
                return null
            }
            if (n < 0 || n > 65535) {
                logger.atWarning()
                    .event("proxy.config.invalid")
                    .field("port", n)
                    .log("Proxy port out of range; ignoring")
                return null
            }
            return n
        }

        /** Converts a shell-style glob (`*.internal.example.com`, `127.*`) to a regex `Pattern`. */
        @JvmStatic
        internal fun compileGlob(pattern: String): Pattern {
            val sb = StringBuilder(pattern.length + 8)
            for (c in pattern) {
                when (c) {
                    '*' -> sb.append(".*")
                    '?' -> sb.append('.')
                    '.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|' -> {
                        sb.append('\\').append(c)
                    }
                    else -> sb.append(c)
                }
            }
            // `Pattern.matches()` semantics require full-string match; CASE_INSENSITIVE so
            // `*.example.com` matches `API.EXAMPLE.COM` (host names are case-insensitive).
            return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE)
        }

        /**
         * Sentinel returned by [resolveNonProxyHosts] when the user requested "bypass everything"
         * (`NO_PROXY=*` or `http.nonProxyHosts=*`). The caller in [fromConfiguration] checks
         * identity equality (via `===`) and returns null so the consumer routes directly.
         */
        private val BYPASS_ALL: List<String> = listOf("*")
    }
}
