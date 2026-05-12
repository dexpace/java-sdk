package org.dexpace.sdk.core.http.pipeline.steps;

import java.util.List;
import java.util.Map;
import org.dexpace.sdk.core.http.auth.BearerToken;
import org.dexpace.sdk.core.http.auth.BearerTokenProvider;

/**
 * Test-only {@link BearerTokenProvider} that returns {@code null} from
 * {@link #fetch(List, Map)}. Implemented in Java so the Kotlin compiler does NOT insert an
 * intrinsic null check at the SAM call boundary — the {@code null} propagates to the
 * step's own {@code ?: error(...)} guard, exercising the otherwise-unreachable branch in
 * {@code BearerTokenAuthStep.fetchFresh}.
 *
 * <p>Cf. the Kotlin equivalent in {@code AuthStepTest} which returns null via
 * {@code (null as BearerToken?) as BearerToken}: that path is still subject to the
 * intrinsic null check inserted by the Kotlin caller at the SAM site and therefore
 * NPE's before reaching the step's defensive guard. The Java implementation here is
 * exempt because Java has no intrinsic null check at this boundary.
 */
public final class NullReturningBearerTokenProvider implements BearerTokenProvider {
    @Override
    public BearerToken fetch(List<String> scopes, Map<String, ? extends Object> params) {
        return null;
    }
}
