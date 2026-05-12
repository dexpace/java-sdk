package org.dexpace.sdk.core.http.auth

/**
 * Marker for the SDK's credential types. Sealed so callers — and the auth pipeline steps
 * that consume them — can rely on an exhaustive set of variants at compile time.
 *
 * Concrete variants:
 *  - [KeyCredential] — static API key stamped into a configured header (default
 *    `Authorization`), optionally with a scheme prefix like `SharedAccessKey`.
 *  - [NamedKeyCredential] — name + key pair used by SAS-style auth where the name is part
 *    of the canonical signing input.
 *  - [BearerToken] — OAuth-style access token with an optional expiry instant; refresh is
 *    driven by [BearerTokenProvider] inside [org.dexpace.sdk.core.http.pipeline.steps.BearerTokenAuthStep].
 *
 * Construction enforces non-blank fields at the credential level; HTTPS-only enforcement
 * is the responsibility of the consuming auth step.
 */
sealed interface Credential
