package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.pipeline.HttpStep
import org.dexpace.sdk.core.http.pipeline.Stage

/**
 * Pillar step at [Stage.REDIRECT]. Follows 3xx responses by re-issuing the downstream
 * pipeline against the `Location` URI, strips `Authorization` before reissue (security-
 * critical — server-supplied redirect targets must not receive caller credentials), and
 * detects loops via the set of attempted URIs.
 *
 * The base is `abstract` because the stage is locked to [Stage.REDIRECT] at the type
 * level — users implementing custom redirect behavior override [process] but inherit
 * the pillar slot. The shipped concrete implementation is [DefaultRedirectStep].
 */
public abstract class RedirectStep : HttpStep {
    final override val stage: Stage = Stage.REDIRECT
}
