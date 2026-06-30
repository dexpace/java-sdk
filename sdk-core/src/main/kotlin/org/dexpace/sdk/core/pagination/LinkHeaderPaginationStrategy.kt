/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import java.net.MalformedURLException
import java.net.URL

/**
 * RFC 5988 `Link` header [PaginationStrategy]. The server emits a `Link` header on each
 * page response with a `rel="next"` segment carrying the next page's full URL; the
 * strategy follows that URL while preserving the initial request's method, headers, and
 * body.
 *
 * Wire shape:
 *
 * - Page-1 request: caller-supplied (no special query param required).
 * - Page-N response: `Link: <https://api.example.com/things?page=N+1>; rel="next", <...>; rel="last"`.
 * - End of stream: response either omits the `Link` header entirely or omits the
 *   `rel="next"` segment.
 *
 * Per RFC 8288 a `rel="next"` target may be a relative reference (e.g.
 * `</things?page=2>; rel="next"`). The strategy resolves such targets against the
 * originating page's response URL, so both absolute and relative `next` links paginate
 * correctly. A target that cannot be resolved into a valid URL is treated as end-of-stream
 * rather than aborting iteration.
 *
 * The parser implements the subset of RFC 5988 that REST APIs actually use:
 *
 * - Comma-separated link-values; commas inside quoted parameter values are not split.
 * - Each link-value is `<url>; param=value; param="quoted value"`.
 * - The `rel` parameter may be quoted or unquoted, and may carry multiple
 *   space-separated relation types (we match if any equals `next`).
 *
 * @param T Element type extracted from the response.
 * @property itemsExtractor Reads the list of items from the response. Called once per
 *   page; must drain the response body synchronously.
 * @property linkHeader Header name to read (default `"Link"`).
 */
public class LinkHeaderPaginationStrategy<T>
    @JvmOverloads
    constructor(
        private val itemsExtractor: (Response) -> List<T>,
        private val linkHeader: String = "Link",
    ) : PaginationStrategy<T> {
        override fun parse(
            response: Response,
            initialRequest: Request,
        ): PageInfo<T> {
            val items: List<T> = itemsExtractor(response)
            // Some servers emit multiple `Link` headers (one per link-value) instead of a single
            // comma-separated header. Joining the values with ',' normalizes both wire shapes into
            // one string so a single parser handles either. An empty header list joins to "", which
            // extractNextUrl already maps to null (no `rel="next"`).
            val nextUrlString: String? =
                extractNextUrl(response.headers.values(linkHeader).joinToString(separator = ","))
            // A `rel="next"` target that cannot be parsed into a valid URL ends the stream (null)
            // rather than aborting iteration with a MalformedURLException from resolveNextUrl.
            val nextUrl: URL? =
                if (nextUrlString.isNullOrEmpty()) null else resolveNextUrl(response, nextUrlString)
            val nextRequest: Request? =
                if (nextUrl != null) RequestRebuilder.withUrl(initialRequest, nextUrl) else null
            return PageInfo(items = items, nextRequest = nextRequest)
        }

        /**
         * Resolves [nextUrlString] (which may be an absolute or relative reference per
         * RFC 8288) against the originating page's response URL. Returns `null` — signalling
         * end-of-stream — when the target cannot be resolved into a valid URL.
         */
        private fun resolveNextUrl(
            response: Response,
            nextUrlString: String,
        ): URL? =
            try {
                val base = response.request.url
                val ref = nextUrlString.trim()
                if (ref.startsWith("?")) {
                    // RFC 3986 query-only reference: keep the base's FULL path and replace only the
                    // query. Both URL(base, ref) and URI.resolve follow the older RFC 2396 here and
                    // drop the base's last path segment (".../repo/?page=2" instead of
                    // ".../repo/issues?page=2"), pointing the next page at the wrong resource. Splice
                    // the already-encoded base components directly so nothing is re-encoded.
                    URL("${base.protocol}://${base.authority}${base.path}$ref")
                } else {
                    URL(base, nextUrlString)
                }
            } catch (ignored: MalformedURLException) {
                // Unresolvable next link (e.g. unknown scheme): stop paginating rather than letting
                // the exception abort iteration.
                null
            }

        /**
         * Parses an RFC 5988 `Link` header value (possibly the concatenation of multiple
         * header values) and returns the URL of the first link-value whose `rel` parameter
         * contains the token `next`, or `null` if no such link-value exists.
         */
        private fun extractNextUrl(header: String): String? =
            splitLinkValues(header)
                .asSequence()
                .mapNotNull { parseLinkValue(it) }
                .firstOrNull { it.second.any { rel -> rel.equals("next", ignoreCase = true) } }
                ?.first

        /**
         * Splits an RFC 5988 `Link` header into individual link-values. Commas inside
         * angle brackets or quoted strings do NOT split.
         */
        @Suppress("ReturnCount")
        private fun splitLinkValues(header: String): List<String> {
            val out: MutableList<String> = ArrayList()
            val current = StringBuilder()
            var inAngles = false
            var inQuotes = false
            var i = 0
            while (i < header.length) {
                val c = header[i]
                when {
                    c == '\\' && inQuotes && i + 1 < header.length -> {
                        current.append(c).append(header[i + 1])
                        i += 2
                        continue
                    }
                    c == '"' && !inAngles -> {
                        inQuotes = !inQuotes
                        current.append(c)
                    }
                    c == '<' && !inQuotes -> {
                        inAngles = true
                        current.append(c)
                    }
                    c == '>' && !inQuotes -> {
                        inAngles = false
                        current.append(c)
                    }
                    c == ',' && !inAngles && !inQuotes -> {
                        if (current.isNotEmpty()) {
                            out.add(current.toString().trim())
                            current.setLength(0)
                        }
                    }
                    else -> current.append(c)
                }
                i++
            }
            if (current.isNotEmpty()) {
                out.add(current.toString().trim())
            }
            return out
        }

        /**
         * Parses a single link-value (e.g. `<https://api.example.com/x>; rel="next first"`)
         * into a `(url, [rel tokens])` pair, or `null` if the link-value is malformed.
         */
        private fun parseLinkValue(value: String): Pair<String, List<String>>? {
            val start = value.indexOf('<')
            val end = value.indexOf('>', start + 1)
            if (start < 0 || end < 0) return null
            val url = value.substring(start + 1, end).trim()
            if (url.isEmpty()) return null
            val rest = value.substring(end + 1)
            val rels: List<String> = extractRelTokens(rest)
            return Pair(url, rels)
        }

        /**
         * Returns the list of relation tokens declared by `rel=...` (or `rel="..."`) in
         * a link-value's parameter section.
         */
        private fun extractRelTokens(paramSection: String): List<String> {
            // Iterate over `; key=value` pairs respecting quotes.
            val pairs: MutableList<String> = ArrayList()
            val sb = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < paramSection.length) {
                val c = paramSection[i]
                when {
                    c == '\\' && inQuotes && i + 1 < paramSection.length -> {
                        sb.append(c).append(paramSection[i + 1])
                        i += 2
                        continue
                    }
                    c == '"' -> {
                        inQuotes = !inQuotes
                        sb.append(c)
                    }
                    c == ';' && !inQuotes -> {
                        if (sb.isNotEmpty()) {
                            pairs.add(sb.toString().trim())
                            sb.setLength(0)
                        }
                    }
                    else -> sb.append(c)
                }
                i++
            }
            if (sb.isNotEmpty()) {
                pairs.add(sb.toString().trim())
            }
            return pairs.firstNotNullOfOrNull { pair -> parseRelPair(pair) } ?: emptyList()
        }

        /** Returns the rel-token list for [pair] if it's a `rel=...` parameter, else `null`. */
        private fun parseRelPair(pair: String): List<String>? {
            val eq = pair.indexOf('=')
            if (eq < 0) return null
            val key = pair.substring(0, eq).trim()
            if (!key.equals("rel", ignoreCase = true)) return null
            val rawValue = pair.substring(eq + 1).trim()
            val unquoted: String =
                if (rawValue.length >= 2 && rawValue.startsWith('"') && rawValue.endsWith('"')) {
                    rawValue.substring(1, rawValue.length - 1)
                } else {
                    rawValue
                }
            return unquoted.split(' ', '\t').filter { it.isNotEmpty() }
        }
    }
