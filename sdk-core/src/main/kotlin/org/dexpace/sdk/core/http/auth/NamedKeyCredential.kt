package org.dexpace.sdk.core.http.auth

/**
 * Name + key credential. Used by SAS-style auth where the [name] is part of the canonical
 * signing input — the key on its own is not enough to identify the principal.
 *
 * Stamping logic is service-specific (the canonical string and signing algorithm vary), so
 * there is no shared `*AuthStep` for this credential; callers wire it into their own
 * service-specific signing step.
 *
 * @param name the key identifier; must be non-empty.
 * @param key the secret material; must be non-empty.
 * @throws IllegalArgumentException if [name] or [key] is empty.
 */
class NamedKeyCredential(val name: String, val key: String) : Credential {
    init {
        require(name.isNotEmpty()) { "name must not be empty" }
        require(key.isNotEmpty()) { "key must not be empty" }
    }
}
