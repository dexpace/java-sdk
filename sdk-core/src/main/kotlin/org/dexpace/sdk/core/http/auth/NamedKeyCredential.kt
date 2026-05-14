package org.dexpace.sdk.core.http.auth

/**
 * Name + key credential. Used by SAS-style auth where the [name] is part of the canonical
 * signing input — the key on its own is not enough to identify the principal.
 *
 * Stamping logic is service-specific (the canonical string and signing algorithm vary), so
 * there is no shared `*AuthStep` for this credential; callers wire it into their own
 * service-specific signing step.
 *
 * @param name the key identifier; must not be blank.
 * @param key the secret material; must not be blank.
 * @throws IllegalArgumentException if [name] or [key] is blank.
 */
public class NamedKeyCredential(public val name: String, public val key: String) : Credential {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(key.isNotBlank()) { "key must not be blank" }
    }
}
