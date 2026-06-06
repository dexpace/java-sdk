/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
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
        ): Page<T> {
            val items: List<T> = itemsExtractor(response)
            // Some servers emit multiple Link headers (one per link-value) instead of a single
            // comma-separated header. Join with ',' so a single parser handles both shapes.
            val linkValues: List<String> = response.headers.values(linkHeader)
            val combined: String =
                if (linkValues.isEmpty()) "" else linkValues.joinToString(separator = ",")
            val nextUrlString: String? = if (combined.isEmpty()) null else extractNextUrl(combined)
            val hasNext: Boolean = !nextUrlString.isNullOrEmpty()
            val nextRequest: Request? =
                if (hasNext) {
                    RequestRebuilder.withUrl(initialRequest, URL(nextUrlString))
                } else {
                    null
                }
            return SimplePage(items = items, hasNext = hasNext, nextRequest = nextRequest)
        }

        /**
         * Parses an RFC 5988 `Link` header value (possibly the concatenation of multiple
         * header values) and returns the URL of the first link-value whose `rel` parameter
         * contains the token `next`, or `null` if no such link-value exists.
         */
        private fun extractNextUrl(header: String): String? {
            for (entry in splitLinkValues(header)) {
                val parsed = parseLinkValue(entry) ?: continue
                val rels = parsed.second
                if (rels.any { it.equals("next", ignoreCase = true) }) {
                    return parsed.first
                }
            }
            return null
        }

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
