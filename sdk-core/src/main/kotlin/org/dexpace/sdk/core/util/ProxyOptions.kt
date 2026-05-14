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
 *
 * ## Bypass-all semantics (breaking change from pre-v2 API)
 *
 * The previous API used a `listOf("*")` sentinel value in [nonProxyHosts] to signal
 * "bypass the proxy for every host". That sentinel has been replaced by the explicit
 * [bypassAllHosts] flag. Callers that previously checked `nonProxyHosts == listOf("*")`
 * must now check `bypassAllHosts == true` instead. The [nonProxyHosts] list is now a
 * real host-pattern allow-list and `"*"` in it is treated as a glob matching any host,
 * not as the bypass-all sentinel.
 */
public class ProxyOptions
    @JvmOverloads
    constructor(
        public val type: Type,
        public val address: InetSocketAddress,
        public val nonProxyHosts: List<String> = emptyList(),
        public val username: String? = null,
        public val password: String? = null,
        public val challengeHandler: ChallengeHandler? = null,
        /**
         * When `true`, the proxy is bypassed for every host regardless of [nonProxyHosts].
         * Corresponds to `NO_PROXY=*` or `http.nonProxyHosts=*` in the environment.
         *
         * **Breaking change:** the previous sentinel `listOf("*")` in [nonProxyHosts] has
         * been replaced by this flag. [fromConfiguration] sets this when the configuration
         * specifies a bare `"*"` wildcard, and returns `null` so the caller routes directly
         * without a proxy. When constructing [ProxyOptions] directly, set this flag rather
         * than placing `"*"` in [nonProxyHosts].
         */
        public val bypassAllHosts: Boolean = false,
    ) {
        // Pre-compiled at construction so per-request matches don't re-compile.
        private val nonProxyPatterns: List<Pattern> = nonProxyHosts.map { compileGlob(it) }

        /**
         * Returns `true` if [host] should bypass the proxy.
         *
         * Short-circuits immediately when [bypassAllHosts] is set. Otherwise checks whether
         * [host] matches any of the configured [nonProxyHosts] glob patterns.
         */
        public fun bypassesProxy(host: String): Boolean =
            bypassAllHosts || nonProxyPatterns.any { it.matcher(host).matches() }

        /**
         * Renders the proxy configuration without leaking credentials. [username] and [password]
         * are masked to `***` when present so accidental log captures (and the JDK's default
         * `Object.toString()` exposure) do not surface secrets.
         *
         * If a custom [challengeHandler] holds credentials, override its `toString()` to mask
         * them — this method does not redact nested handlers.
         */
        override fun toString(): String =
            "ProxyOptions(type=$type, address=$address, nonProxyHosts=$nonProxyHosts, " +
                "username=${if (username != null) "***" else null}, " +
                "password=${if (password != null) "***" else null})"

        /**
         * Proxy protocol. [HTTP] is a CONNECT-tunneling or forward HTTP proxy; [SOCKS4] / [SOCKS5] are
         * the corresponding SOCKS variants. Transport adapters dispatch on this value to pick the right
         * `java.net.Proxy.Type`.
         */
        public enum class Type { HTTP, SOCKS4, SOCKS5 }

        public companion object {
            private val logger = ClientLogger(ProxyOptions::class)

            // Splitters per source: env var `NO_PROXY` uses comma; system property `http.nonProxyHosts`
            // uses pipe. Each respects a preceding backslash as an escape so `"a\,b,c"` -> ["a,b", "c"].
            private val ENV_SPLIT: Pattern = Pattern.compile("(?<!\\\\),")
            private val PROP_SPLIT: Pattern = Pattern.compile("(?<!\\\\)\\|")

            // Upper bound for TCP port numbers (IANA: 0-65535, 16-bit unsigned). Used to validate
            // proxy port configuration before constructing an InetSocketAddress.
            private const val MAX_PORT = 65535

            // Extra slack for the StringBuilder used by `compileGlob` so common glob expansions
            // (`*` → `.*`, `.` → `\\.`) don't reallocate. Tuned, not load-bearing.
            private const val GLOB_BUILDER_SLACK = 8

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
             *
             * Layered config resolution legitimately fans out into several short-circuit
             * returns (bypass-all, sysprop layer hit, env-var fallback). Refactoring to a
             * single return would obscure the precedence rules.
             */
            @Suppress("ReturnCount")
            @JvmStatic
            public fun fromConfiguration(config: Configuration): ProxyOptions? {
                // 1. System property layer first: prefer https.* then fall back to http.*.
                // Use getProperty (case-preserving) — not get(...) — because these keys live as
                // system properties only and the JVM convention is camelCase (`https.proxyHost`).
                val httpsHost = config.getProperty("https.proxyHost")
                val httpHost = config.getProperty("http.proxyHost")
                val sysHost = httpsHost ?: httpHost
                val sysPortRaw =
                    when {
                        httpsHost != null -> config.getProperty("https.proxyPort")
                        httpHost != null -> config.getProperty("http.proxyPort")
                        else -> null
                    }
                val sysUser = config.getProperty("https.proxyUser")
                val sysPassword = config.getProperty("https.proxyPassword")

                // Resolve non-proxy host list with system property winning over env var.
                val (nonProxyHosts, bypassAll) = resolveNonProxyHosts(config)
                // NO_PROXY = "*" (or sysprop equivalent) → bypass everything; no proxy applies.
                if (bypassAll) return null

                if (!sysHost.isNullOrEmpty()) {
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
                            .field("message", "Invalid proxy address; ignoring").log()
                        null
                    }
                }

                // 2. Env var layer: HTTPS_PROXY then HTTP_PROXY.
                val envProxyUrl = config.get(Configuration.HTTPS_PROXY) ?: config.get(Configuration.HTTP_PROXY)
                if (envProxyUrl.isNullOrEmpty()) return null

                return parseProxyUrl(envProxyUrl, nonProxyHosts)
            }

            /**
             * Parses a full proxy URL (`http://user:pass@proxy:8080`) into a [ProxyOptions].
             * Returns null on any parse/validation failure (proxies are optional — never throw).
             */
            private fun parseProxyUrl(
                url: String,
                nonProxyHosts: List<String>,
            ): ProxyOptions? {
                val uri =
                    try {
                        URI(url)
                    } catch (t: Exception) {
                        logger.atWarning()
                            .event("proxy.config.invalid")
                            .field("url", url)
                            .cause(t)
                            .field("message", "Could not parse proxy URL; ignoring").log()
                        return null
                    }

                val host = uri.host
                if (host.isNullOrEmpty()) {
                    logger.atWarning()
                        .event("proxy.config.invalid")
                        .field("url", url)
                        .field("message", "Proxy URL has no host; ignoring").log()
                    return null
                }

                val rawPort = uri.port
                // URI.getPort returns -1 when absent. The user *must* specify a port — we don't
                // guess defaults (80/443) because the request's target scheme is unrelated to the
                // proxy's listen port.
                if (rawPort < 0 || rawPort > MAX_PORT) {
                    logger.atWarning()
                        .event("proxy.config.invalid")
                        .field("url", url)
                        .field("port", rawPort)
                        .field("message", "Proxy URL port out of range; ignoring").log()
                    return null
                }

                val (user, pass) = extractUserInfo(uri.userInfo)
                return buildOptions(host, rawPort, user, pass, nonProxyHosts)
            }

            /**
             * Splits a URI `userinfo` component (`user`, `user:pass`, or absent) into a
             * username / password pair. The password may contain colons, so the split is on
             * the first colon only.
             */
            private fun extractUserInfo(userinfo: String?): Pair<String?, String?> {
                if (userinfo.isNullOrEmpty()) return null to null
                val colon = userinfo.indexOf(':')
                return if (colon >= 0) {
                    userinfo.substring(0, colon) to userinfo.substring(colon + 1)
                } else {
                    userinfo to null
                }
            }

            /**
             * Constructs a [ProxyOptions] for the given [host] / [port] / credential triple.
             * Returns null and logs at warning when [InetSocketAddress] rejects the arguments.
             */
            private fun buildOptions(
                host: String,
                port: Int,
                user: String?,
                pass: String?,
                nonProxyHosts: List<String>,
            ): ProxyOptions? =
                try {
                    ProxyOptions(
                        type = Type.HTTP,
                        address = InetSocketAddress(host, port),
                        nonProxyHosts = nonProxyHosts,
                        username = user,
                        password = pass,
                    )
                } catch (t: IllegalArgumentException) {
                    logger.atWarning()
                        .event("proxy.config.invalid")
                        .field("host", host)
                        .field("port", port)
                        .cause(t)
                        .field("message", "Invalid proxy address; ignoring").log()
                    null
                }

            /**
             * Resolves the effective non-proxy host list.
             *
             * System property `http.nonProxyHosts` (pipe-separated) wins over env var
             * `NO_PROXY` (comma-separated). Returns a pair of (hostList, bypassAll) where
             * `bypassAll` is `true` when the configured value is the single bare `*` wildcard
             * — the caller returns `null` in that case so the consumer routes directly.
             */
            private fun resolveNonProxyHosts(config: Configuration): Pair<List<String>, Boolean> {
                val sysProp = config.getProperty("http.nonProxyHosts")
                if (!sysProp.isNullOrEmpty()) {
                    val parts = splitAndUnescape(sysProp, PROP_SPLIT, '|')
                    return if (parts.size == 1 && parts[0] == "*") emptyList<String>() to true else parts to false
                }
                val envVar = config.get(Configuration.NO_PROXY)
                if (!envVar.isNullOrEmpty()) {
                    val parts = splitAndUnescape(envVar, ENV_SPLIT, ',')
                    return if (parts.size == 1 && parts[0] == "*") emptyList<String>() to true else parts to false
                }
                return emptyList<String>() to false
            }

            /**
             * Split [raw] by [splitter] and strip the backslash escape character preceding the
             * literal [separator] so `"a\,b,c"` -> `["a,b", "c"]`. Empty fragments are dropped.
             */
            private fun splitAndUnescape(
                raw: String,
                splitter: Pattern,
                separator: Char,
            ): List<String> {
                val escaped = "\\$separator"
                val literal = separator.toString()
                return splitter.split(raw)
                    .filter { it.isNotEmpty() }
                    .map { it.replace(escaped, literal).trim() }
            }

            /** Strict port parser. Returns null when [raw] is missing, non-numeric, or out of range. */
            private fun parsePort(raw: String?): Int? {
                if (raw == null) return null
                val n = raw.toIntOrNull()
                if (n == null) {
                    logger.atWarning()
                        .event("proxy.config.invalid")
                        .field("port", raw)
                        .field("message", "Proxy port is not numeric; ignoring").log()
                    return null
                }
                if (n < 0 || n > MAX_PORT) {
                    logger.atWarning()
                        .event("proxy.config.invalid")
                        .field("port", n)
                        .field("message", "Proxy port out of range; ignoring").log()
                    return null
                }
                return n
            }

            /** Converts a shell-style glob (`*.internal.example.com`, `127.*`) to a regex `Pattern`. */
            internal fun compileGlob(pattern: String): Pattern {
                val sb = StringBuilder(pattern.length + GLOB_BUILDER_SLACK)
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

            // Note: the previous BYPASS_ALL sentinel (listOf("*")) has been replaced by the
            // explicit bypassAllHosts: Boolean field on ProxyOptions. See the class-level KDoc.
        }
    }
