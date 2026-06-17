/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.context

/**
 * Typed key for an ad-hoc per-call override carried in [CallOptions.attributes].
 *
 * The well-known per-call knobs (timeout, response validation, credential) are first-class
 * fields on [CallOptions]; this key type is the open extension channel for everything else a
 * caller — or a future generated operation — may want to thread through the context chain
 * without growing the core options surface.
 *
 * Identity is *by instance*, not by [name]: two keys with the same name are distinct so that
 * independently-defined extensions never collide. Declare each key once as a `private`/`public`
 * constant and reuse it. [name] exists purely for diagnostics and a readable [toString].
 *
 * The phantom type parameter [T] ties a key to the value type stored under it, so
 * [CallOptions.get] returns a correctly-typed value with no cast at the call site.
 *
 * @param T The type of value stored under this key.
 * @property name Human-readable label used only for diagnostics.
 */
public class CallOption<T : Any>(
    public val name: String,
) {
    override fun toString(): String = "CallOption($name)"
}
