# Azure SDK Comparison Report

A line-by-line deep read of the Azure Java SDK code currently in `sdk-core/src/main/java/` (for learning only — never committed) against what we have today and what we've planned. Organized by impact, with code snippets so the architectural choices are concrete.

The big finding up front: **most of the individual features I previously suggested (retry policy, redirect policy, auth policy, instrumentation policy) sit on top of a pipeline architecture that we don't have yet.** Building any of them against our current placeholder `RequestPipeline` would produce broken code. The first move is rebuilding the pipeline.

---

## Table of contents

1. [Pipeline architecture (the single biggest gap)](#1-pipeline-architecture-the-single-biggest-gap)
2. [Retry policy — `Retry-After` parsing, `next.copy()`, exception classification](#2-retry-policy)
3. [Redirect policy — Authorization stripping, loop detection](#3-redirect-policy)
4. [Auth — HTTPS-only enforcement, credential types, WWW-Authenticate challenges](#4-auth)
5. [Body / I/O layer — what we already match or beat](#5-body--io-layer)
6. [HTTP semantic types — `HttpRange`, `ETag`, `HttpRequestConditions`](#6-http-semantic-types)
7. [Headers — interned + case-insensitive identity, multi-value support](#7-headers)
8. [`HttpResponseException` carrying retryability](#8-httpresponseexception-carrying-retryability)
9. [Server-Sent Events](#9-server-sent-events)
10. [Pagination](#10-pagination)
11. [Observability — `ClientLogger`, `UrlRedactor`, `Tracer`/`Span`](#11-observability)
12. [Configuration + proxy support](#12-configuration--proxy-support)
13. [Annotation-based interface clients (Retrofit/Azure paradigm)](#13-annotation-based-interface-clients-retrofitazure-paradigm)
14. [Small utilities](#14-small-utilities)
15. [Prioritized recommendations](#15-prioritized-recommendations)
16. [What we should *not* adopt](#16-what-we-should-not-adopt)
17. [Suggested next two days of work](#17-suggested-next-two-days-of-work)

---

## 1. Pipeline architecture (the single biggest gap)

Our current pipeline (`sdk-core/src/main/kotlin/org/dexpace/sdk/core/pipeline/`) is a placeholder list-of-steps:

```kotlin
// our code today
fun interface RequestPipeline {
    fun execute(request: Request, context: DispatchContext): Request
    val steps: List<RequestPipelineStep> get() = emptyList()
}

interface RequestPipelineStep : PipelineStep<Request, Request>
```

Adding steps means knowing the right order. There's no concept of "this is the retry pillar; logging goes *after* retry." There's no way for a policy to re-run its successors (which both retry and redirect require).

**Azure's design** uses position-based slot assignment:

```java
// Azure SDK
public final class HttpPipelinePosition implements ExpandableEnum<Integer> {
    public static final HttpPipelinePosition BEFORE_REDIRECT       = new HttpPipelinePosition(1000);
    static final HttpPipelinePosition REDIRECT                     = new HttpPipelinePosition(2000); // pillar
    public static final HttpPipelinePosition AFTER_REDIRECT        = new HttpPipelinePosition(3000);
    static final HttpPipelinePosition RETRY                        = new HttpPipelinePosition(4000); // pillar
    public static final HttpPipelinePosition AFTER_RETRY           = new HttpPipelinePosition(5000);
    static final HttpPipelinePosition AUTHENTICATION               = new HttpPipelinePosition(6000); // pillar
    public static final HttpPipelinePosition AFTER_AUTHENTICATION  = new HttpPipelinePosition(7000);
    static final HttpPipelinePosition INSTRUMENTATION              = new HttpPipelinePosition(8000); // pillar
    public static final HttpPipelinePosition AFTER_INSTRUMENTATION = new HttpPipelinePosition(9000);
}
```

Each policy declares its position:

```java
// Azure SDK — HttpRetryPolicy
@Override public HttpPipelinePosition getPipelinePosition() {
    return HttpPipelinePosition.RETRY;
}

// Azure SDK — UserAgentPolicy
@Override public final HttpPipelinePosition getPipelinePosition() {
    return HttpPipelinePosition.BEFORE_REDIRECT;
}
```

The builder slots policies into ordered buckets and enforces "exactly one pillar":

```java
// Azure SDK — HttpPipelineBuilder
private final LinkedList<HttpPipelinePolicy> beforeRedirect = new LinkedList<>();
private HttpRedirectPolicy redirectPolicy;                                       // pillar singleton
private final LinkedList<HttpPipelinePolicy> betweenRedirectAndRetry = new LinkedList<>();
private HttpRetryPolicy retryPolicy;                                             // pillar singleton
private final LinkedList<HttpPipelinePolicy> betweenRetryAndAuthentication = new LinkedList<>();
private HttpCredentialPolicy credentialPolicy;                                   // pillar singleton
private final LinkedList<HttpPipelinePolicy> betweenAuthenticationAndInstrumentation = new LinkedList<>();
private HttpInstrumentationPolicy instrumentationPolicy;                         // pillar singleton
private final LinkedList<HttpPipelinePolicy> afterInstrumentation = new LinkedList<>();

private boolean tryAddPillar(HttpPipelinePolicy policy) {
    HttpPipelinePosition order = policy.getPipelinePosition();
    if (order == HttpPipelinePosition.RETRY) {
        previous = retryPolicy;
        retryPolicy = (HttpRetryPolicy) policy;   // last-write-wins with a warning
        added = true;
    }
    // ...
    if (previous != null) {
        LOGGER.atWarning().log("A pillar policy was replaced in the pipeline.");
    }
    return added;
}
```

And — the piece that makes stateful policies work at all — `HttpPipelineNextPolicy.copy()`:

```java
// Azure SDK
public class HttpPipelineNextPolicy {
    private final HttpPipelineCallState state;

    public Response<BinaryData> process() {
        HttpPipelinePolicy nextPolicy = state.getNextPolicy();
        if (nextPolicy == null) {
            return state.getPipeline().getHttpClient().send(state.getHttpRequest());
        } else {
            return nextPolicy.process(state.getHttpRequest(), this);
        }
    }

    /** Must be used when a re-request is made in the pipeline. */
    public HttpPipelineNextPolicy copy() {
        return new HttpPipelineNextPolicy(state.copy());
    }
}
```

`next.copy()` is what lets `HttpRetryPolicy` re-issue the downstream chain on retry:

```java
// Azure SDK — HttpRetryPolicy.attempt(...)
try {
    response = next.copy().process();    // fresh state for this attempt
} catch (RuntimeException err) {
    if (shouldRetryException(retryCondition)) {
        Thread.sleep(delayDuration.toMillis());
        return attempt(httpRequest, next, tryCount + 1, suppressedLocal);
    }
}
```

**What our pipeline is missing:**

1. **Position declaration** on each step.
2. **Pillar slot enforcement** in the builder (one redirect, one retry, one auth, one instrumentation).
3. **`next.copy()`** — without this, no policy can correctly re-run downstream policies.
4. **Singleton enforcement with last-wins + warning log.**
5. **The pipeline-call-state object** tracking the current policy index.

**Until this is fixed, every other policy I describe below is structurally broken.** That's the reordering of my prior answer: the pipeline rebuild becomes priority 0, before logger / redactor / instrumentation policy.

---

## 2. Retry policy

A real production retry policy is more nuanced than "exponential backoff with N attempts." Azure's `HttpRetryPolicy` handles three things we haven't planned for.

### 2.1 `Retry-After` header parsing (three variants)

```java
// Azure SDK — HttpRetryPolicy
private static final HttpHeaderName RETRY_AFTER_MS_HEADER      = HttpHeaderName.fromString("retry-after-ms");
private static final HttpHeaderName X_MS_RETRY_AFTER_MS_HEADER = HttpHeaderName.fromString("x-ms-retry-after-ms");

private static Duration getRetryAfterFromHeaders(HttpHeaders headers, Supplier<OffsetDateTime> nowSupplier) {
    Duration retryDelay = tryGetRetryDelay(headers, X_MS_RETRY_AFTER_MS_HEADER, HttpRetryPolicy::tryGetDelayMillis);
    if (retryDelay != null) return retryDelay;

    retryDelay = tryGetRetryDelay(headers, RETRY_AFTER_MS_HEADER, HttpRetryPolicy::tryGetDelayMillis);
    if (retryDelay != null) return retryDelay;

    // Standard 'Retry-After' — either seconds (long) or RFC 1123 date.
    retryDelay = tryGetRetryDelay(headers, HttpHeaderName.RETRY_AFTER,
        headerValue -> tryParseLongOrDateTime(headerValue, nowSupplier));
    return retryDelay;
}

private static Duration tryParseLongOrDateTime(String value, Supplier<OffsetDateTime> nowSupplier) {
    long delaySeconds;
    try {
        OffsetDateTime retryAfter = new DateTimeRfc1123(value).getDateTime();
        delaySeconds = nowSupplier.get().until(retryAfter, ChronoUnit.SECONDS);
    } catch (DateTimeException ex) {
        delaySeconds = tryParseLong(value);
    }
    return (delaySeconds >= 0) ? Duration.ofSeconds(delaySeconds) : null;
}
```

Without this, hitting a rate-limited API (which usually responds with `Retry-After: 30`) means we ignore the server's guidance and retry on our own exponential schedule — causing hammering and bans.

### 2.2 Exponential backoff with bounded random jitter

```java
// Azure SDK — HttpRetryPolicy
private static final double JITTER_FACTOR = 0.05;

private Duration calculateRetryDelay(HttpRetryCondition retryCondition) {
    long baseDelayNanos = baseDelay.toNanos();
    long maxDelayNanos  = maxDelay.toNanos();
    long delayWithJitterInNanos = ThreadLocalRandom.current().nextLong(
        (long) (baseDelayNanos * (1 - JITTER_FACTOR)),
        (long) (baseDelayNanos * (1 + JITTER_FACTOR))
    );
    return Duration.ofNanos(Math.min(
        (1L << retryCondition.getTryCount()) * delayWithJitterInNanos,
        maxDelayNanos
    ));
}
```

±5% jitter applied to the base; powers-of-two multiplier per attempt; capped by `maxDelay`. Prevents thundering-herd when many clients retry simultaneously.

### 2.3 Default retry classification

```java
// Azure SDK — implicit via RetryUtils.isRetryable(statusCode)
// Retries: 408 (Request Timeout), 429 (Too Many Requests), all 5xx except 501 (Not Implemented) and 505 (HTTP Version Not Supported)
// For exceptions: walks the cause chain looking for IOException or TimeoutException
```

The exception-chain walk is subtle:

```java
// Azure SDK — HttpRetryPolicy
private boolean shouldRetryException(HttpRetryCondition retryCondition) {
    if (retryCondition.getTryCount() >= maxRetries) return false;

    Throwable causalThrowable = retryCondition.getException().getCause();
    while (causalThrowable instanceof IOException || causalThrowable instanceof TimeoutException) {
        if (shouldRetryCondition.test(retryCondition)) return true;
        causalThrowable = causalThrowable.getCause();
    }
    return false;
}
```

A wrapped `IOException` (e.g., inside a `RuntimeException("Network error", new SocketTimeoutException())`) still triggers retry. We'd want the same behavior.

### 2.4 `InterruptedException` handling during sleep

```java
// Azure SDK — HttpRetryPolicy
try {
    Thread.sleep(millis);
} catch (InterruptedException ie) {
    interrupted = true;
    err.addSuppressed(ie);
    logger.atWarning().setThrowable(ie).log();
}
if (interrupted) {
    throw err;   // abort retries; surface the interrupt as suppressed
}
```

Interrupt aborts the retry loop without consuming the interrupt status further. Suppressed exception preserves diagnostics.

### 2.5 Accumulated suppressed exceptions

Each retry's exception is added as a suppressed exception on the final one:

```java
// Azure SDK — HttpRetryPolicy
List<Exception> suppressedLocal = suppressed == null ? new LinkedList<>() : suppressed;
suppressedLocal.add(err);
return attempt(httpRequest, next, tryCount + 1, suppressedLocal);

// ... eventually on final failure:
if (suppressed != null) {
    suppressed.forEach(err::addSuppressed);
}
throw err;
```

When the caller gets the final exception, they can see every attempt's failure via `Throwable.getSuppressed()`. Debugging gold.

---

## 3. Redirect policy

Two non-obvious safety features:

### 3.1 Strip Authorization on redirect

```java
// Azure SDK — HttpRedirectPolicy
private void createRedirectRequest(Response<?> redirectResponse) {
    // Clear the authorization header to avoid the client to be redirected to an untrusted third party server
    // causing it to leak your authorization token to.
    redirectResponse.getRequest().getHeaders().remove(HttpHeaderName.AUTHORIZATION);
    redirectResponse.getRequest().setUri(redirectResponse.getHeaders().getValue(this.locationHeader));
    redirectResponse.close();
}
```

If we don't do this, a malicious `Location: https://attacker.example.com/...` response steals bearer tokens.

### 3.2 Loop detection via URI set

```java
// Azure SDK — HttpRedirectPolicy.defaultShouldAttemptRedirect
if (attemptedRedirectUris.contains(redirectUri)) {
    logRedirect(logger, true, redirectUri, tryCount, method,
        "Request was redirected more than once to the same URI.", context);
    return false;
}
attemptedRedirectUris.add(redirectUri);
```

`LinkedHashSet<String>` per request. Stops `A → B → A → B → ...` loops cheaply.

### 3.3 Method-allow-listing

```java
// Azure SDK — HttpRedirectPolicy
private static final EnumSet<HttpMethod> DEFAULT_REDIRECT_ALLOWED_METHODS
    = EnumSet.of(HttpMethod.GET, HttpMethod.HEAD);
```

`POST` etc. do not redirect by default. Sane: re-issuing a POST to a new URL is rarely what the caller wants, and the body might not be replayable.

### 3.4 Status code set

```java
private static final int PERMANENT_REDIRECT_STATUS_CODE = 308;
private static final int TEMPORARY_REDIRECT_STATUS_CODE = 307;

private boolean isValidRedirectStatusCode(int statusCode) {
    return statusCode == HttpURLConnection.HTTP_MOVED_TEMP   // 302
        || statusCode == HttpURLConnection.HTTP_MOVED_PERM   // 301
        || statusCode == PERMANENT_REDIRECT_STATUS_CODE      // 308
        || statusCode == TEMPORARY_REDIRECT_STATUS_CODE;     // 307
}
```

Note: **303 (See Other)** is *not* on the list because it has special method-rewriting semantics (POST → GET). Worth confirming whether we want that or to treat 303 explicitly.

---

## 4. Auth

### 4.1 HTTPS-only enforcement (fail-fast on insecure scheme)

```java
// Azure SDK — KeyCredentialPolicy
@Override
public Response<BinaryData> process(HttpRequest httpRequest, HttpPipelineNextPolicy next) {
    if (!"https".equals(httpRequest.getUri().getScheme())) {
        throw LOGGER.throwableAtError()
            .log("Key credentials require HTTPS to prevent leaking the key.", IllegalStateException::new);
    }
    setCredential(httpRequest.getHeaders());
    return next.process();
}
```

Same pattern in `OAuthBearerTokenAuthenticationPolicy`. This matches the "fail at the earliest possible time" principle we've been applying — except as a policy-level guard, not a body-level one.

### 4.2 Credential type hierarchy

```java
// Azure SDK has separate concrete types
public class KeyCredential                    // static API key
public class NamedKeyCredential               // (name, key) pair
public class AccessToken                      // (token, expiresAt) for OAuth
public interface OAuthTokenCredential         // refresh-capable token provider
```

Our SDK has nothing for credentials. Today a caller would `request.builder().header("Authorization", "Bearer ...")` by hand. A typed hierarchy lets the policy lookup the right credential and lets compile-time catch "you gave a `KeyCredential` to a policy that needs an `OAuthTokenCredential`."

Shape we'd want:

```kotlin
// proposed for our SDK
sealed interface Credential
class KeyCredential(val apiKey: String, val headerName: HttpHeaderName = HttpHeaderName.AUTHORIZATION) : Credential
class BearerToken(val token: String, val expiresAt: Instant?) : Credential
interface BearerTokenProvider {
    fun fetch(): BearerToken                  // can refresh; caller caches
}
```

### 4.3 `WWW-Authenticate` challenge handling

For enterprise / on-prem APIs that issue `401 + WWW-Authenticate: Digest realm="..."`, Azure has a composable challenge handler system:

```java
// Azure SDK — ChallengeHandler
public interface ChallengeHandler {
    void handleChallenge(HttpRequest request, Response<BinaryData> response, boolean isProxy);
    boolean canHandle(Response<BinaryData> response, boolean isProxy);

    static ChallengeHandler of(ChallengeHandler... handlers) {
        return new CompositeChallengeHandler(Arrays.asList(handlers));   // chain Digest > Basic
    }
}

public class BasicChallengeHandler implements ChallengeHandler {
    private final String authHeader;   // pre-built "Basic <base64(user:pass)>"

    public BasicChallengeHandler(String username, String password) {
        String token = username + ":" + password;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }
}
```

Plus a `DigestChallengeHandler` that handles `qop`, `nc`, `cnonce`, MD5 hashing per RFC 2617.

This is a missing feature for us, but only matters if our target services use these schemes. Skip for v0 if we're targeting modern bearer-token APIs only.

### 4.4 Per-request auth opt-out

```java
// Azure SDK — OAuthBearerTokenAuthenticationPolicy
AuthMetadata authMetadata = (AuthMetadata) httpRequest.getContext().getMetadata(IO_CLIENTCORE_AUTH_METADATA);
if (authMetadata != null) {
    List<AuthScheme> authSchemes = authMetadata.getAuthSchemes();
    if (CoreUtils.isNullOrEmpty(authSchemes) || authSchemes.contains(AuthScheme.NO_AUTH)) {
        return next.process();   // skip auth for this request
    }
}
```

Per-request `AuthMetadata` in the context can specify `AuthScheme.NO_AUTH` (e.g., login endpoints, public endpoints). The auth policy honors it. Useful but a v2 feature.

### 4.5 401 challenge → re-authorize hook

```java
// Azure SDK — OAuthBearerTokenAuthenticationPolicy
Response<BinaryData> httpResponse = next.process();
String authHeader = httpResponse.getHeaders().getValue(HttpHeaderName.WWW_AUTHENTICATE);
if (httpResponse.getStatusCode() == 401 && authHeader != null) {
    if (authorizeRequestOnChallenge(httpRequest, httpResponse)) {
        httpResponse.close();
        return nextPolicy.process();   // re-issue with fresh auth
    }
}
return httpResponse;

/** Subclasses override to react to 401 with WWW-Authenticate. */
public boolean authorizeRequestOnChallenge(HttpRequest httpRequest, Response<BinaryData> response) {
    return false;   // default: don't auto-retry
}
```

The default does nothing; subclasses use this to refresh tokens or step up auth.

---

## 5. Body / I/O layer

This is the area we've already invested heavily in. Quick reconciliation:

| Concern | Azure | Ours | Status |
|---|---|---|---|
| Unified body type | `BinaryData` with 8 subclasses | Split `RequestBody`/`ResponseBody` | Different architectural choice; ours is asymmetric to match data flow direction |
| Replayability | `BinaryData.isReplayable` + `toReplayableBinaryData` | `RequestBody.isReplayable` + `toReplayable` | **We match** |
| Mark/reset for cheap replay | `InputStreamBinaryData.canMarkReset` | `RequestBody.create(InputStream, length)` mark/reset path | **We match** |
| File send w/ sendfile-eligible path | `FileBinaryData.writeTo(WritableByteChannel)` | `FileRequestBody.writeTo` via `FileChannel.transferTo` | **We match** |
| Memory-mapped file `ByteBuffer` | `FileChannel.map(READ_ONLY)` | Not yet | **Missing** |
| Tee for request body capture | Buffers full body before send | `TeeSink` streams + captures | **We're better here** (streams instead of buffer-then-send) |
| Body-logging error semantics | Drain throws stop logging | Drain throws → cached error + partial bytes survive | **We're better here** |
| Chunked buffering for unknown-length captures | `StreamUtil.readStreamToListOfByteBuffers` (geometric 8KB→8MB) | Okio segments (fixed 8KB, pooled) | Equivalent — different mechanism, same property |
| `MAX_ARRAY_SIZE` enforcement on snapshots | `BinaryData.MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8` | `Buffer.MAX_BYTE_ARRAY_SIZE` enforced in `OkioBuffer.snapshot()` | **We match** |
| Slice view over an InputStream | `SliceInputStream` | Not yet | **Missing** |
| Pluggable I/O | None — coupled to `java.io`+NIO | `IoProvider` SPI with adapter modules | **We're better here** |

So in this area we already match or beat Azure on most counts. The remaining gaps are minor: memory-mapped files for local hashing, and a slice view for partial-source operations.

---

## 6. HTTP semantic types

Currently a caller of our SDK builds these as raw strings. Azure has typed values.

### 6.1 `HttpRange`

```java
// Azure SDK
public final class HttpRange {
    private final long offset;
    private final Long length;   // null = unbounded

    public HttpRange(long offset)              { this(offset, null); }
    public HttpRange(long offset, Long length) {
        if (offset < 0) throw new IllegalArgumentException();
        if (length != null && length <= 0) throw new IllegalArgumentException();
        this.offset = offset; this.length = length;
    }

    @Override
    public String toString() {
        return (length == null)
            ? "bytes=" + offset + "-"
            : "bytes=" + offset + "-" + (offset + length - 1);
    }
}
```

Shape we'd want (Kotlin):

```kotlin
// proposed
@JvmInline
value class HttpRange private constructor(private val raw: String) {
    fun toHeaderValue(): String = raw

    companion object {
        fun bytes(offset: Long, length: Long? = null): HttpRange {
            require(offset >= 0)
            require(length == null || length > 0)
            return HttpRange(
                if (length == null) "bytes=$offset-"
                else "bytes=$offset-${offset + length - 1}"
            )
        }
        fun suffix(length: Long): HttpRange {
            require(length > 0)
            return HttpRange("bytes=-$length")    // last N bytes
        }
    }
}
```

Composes naturally with our `FileRequestBody(file, position = ..., explicitCount = ...)`.

### 6.2 `ETag`

```java
// Azure SDK
public final class ETag {
    public static final ETag ALL = new ETag("*");
    private static final ETag NULL = new ETag(null);

    public static ETag fromString(String eTag) {
        if (eTag == null) return NULL;
        if ("*".equals(eTag)) return ALL;
        boolean endsWithQuote = eTag.charAt(eTag.length() - 1) == '"';
        boolean startsWithQuote = eTag.charAt(0) == '"';
        boolean startsWithWeakETagPrefix = eTag.startsWith("W/\"");
        if (!endsWithQuote || (!startsWithQuote && !startsWithWeakETagPrefix)) {
            throw new IllegalArgumentException("Invalid ETag: must be null, '*', \"...\", or W/\"...\"");
        }
        return new ETag(eTag);
    }
}
```

Caught at construction. Also note `ETag.ALL` for `If-Match: *` — a common but easy-to-misspell case.

Shape we'd want (Kotlin):

```kotlin
// proposed
@JvmInline
value class ETag private constructor(val value: String) {
    val isWeak: Boolean get() = value.startsWith("W/\"")
    val isWildcard: Boolean get() = value == "*"

    override fun toString(): String = value

    companion object {
        val ALL: ETag = ETag("*")
        fun strong(opaque: String): ETag = ETag("\"$opaque\"")
        fun weak(opaque: String): ETag = ETag("W/\"$opaque\"")
        fun parse(raw: String): ETag {
            require(raw == "*" || raw.endsWith("\"") &&
                    (raw.startsWith("\"") || raw.startsWith("W/\""))) {
                "Invalid ETag: $raw"
            }
            return ETag(raw)
        }
    }
}
```

### 6.3 `HttpRequestConditions`

```java
// Azure SDK
public class HttpRequestConditions extends HttpMatchConditions {
    private OffsetDateTime ifModifiedSince;
    private OffsetDateTime ifUnmodifiedSince;
    // inherits ifMatch, ifNoneMatch as String (note: Azure doesn't use their typed ETag here — design inconsistency)
}
```

Their own typed `ETag` exists but their `HttpMatchConditions.ifMatch` is `String`. Inconsistent — we should fix when porting:

```kotlin
// proposed
class RequestConditions private constructor(
    val ifMatch: ETag? = null,
    val ifNoneMatch: ETag? = null,
    val ifModifiedSince: Instant? = null,
    val ifUnmodifiedSince: Instant? = null,
) {
    fun applyTo(headers: Headers.Builder): Headers.Builder {
        ifMatch?.let         { headers.set(HttpHeaderName.IF_MATCH, it.toString()) }
        ifNoneMatch?.let     { headers.set(HttpHeaderName.IF_NONE_MATCH, it.toString()) }
        ifModifiedSince?.let { headers.set(HttpHeaderName.IF_MODIFIED_SINCE, DateTimeRfc1123.format(it)) }
        ifUnmodifiedSince?.let { headers.set(HttpHeaderName.IF_UNMODIFIED_SINCE, DateTimeRfc1123.format(it)) }
        return headers
    }

    class Builder { /* fluent */ }
}
```

---

## 7. Headers

### 7.1 Interned, case-insensitive-equal `HttpHeaderName`

```java
// Azure SDK
public final class HttpHeaderName implements ExpandableEnum<String> {
    private static final Map<String, HttpHeaderName> VALUES = new ConcurrentHashMap<>();
    private final String caseSensitive;
    private final String caseInsensitive;

    private HttpHeaderName(String name) {
        this.caseSensitive = name;
        this.caseInsensitive = name.toLowerCase();
    }

    public static HttpHeaderName fromString(String name) {
        if (name == null) return null;
        return VALUES.computeIfAbsent(name, HttpHeaderName::new);
    }

    @Override public int hashCode()    { return caseInsensitive.hashCode(); }
    @Override public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof HttpHeaderName)) return false;
        return Objects.equals(caseInsensitive, ((HttpHeaderName) obj).caseInsensitive);
    }

    public static final HttpHeaderName ACCEPT = fromString("Accept");
    // ~150 well-known constants
}
```

Notice three properties:
- **Interned** — `ConcurrentHashMap` cache so repeated `fromString("Content-Type")` returns the same instance. Saves allocations on hot paths.
- **Case-preserving on the wire** — `caseSensitive` is what got passed in; that's what goes on the wire.
- **Case-insensitive identity** — `equals`/`hashCode` use lowercase. `fromString("Content-Type") == fromString("content-type")` is true under `.equals()` even though they're not `===`.

We need to audit our `Headers.kt` to confirm it uses the same identity rules. Lots of HTTP correctness bugs come from case-sensitive header lookups.

### 7.2 Multi-value headers as `List<String>`

```java
// Azure SDK
public HttpHeaders add(HttpHeaderName name, String value) {
    headers.compute(name, (key, header) -> {
        if (header == null) return new HttpHeader(name, value);
        else { header.addValue(value); return header; }
    });
}

public HttpHeaders set(HttpHeaderName name, List<String> values) { ... }
public String getValue(HttpHeaderName name) { ... }       // first value (or comma-joined)
public List<String> getValues(HttpHeaderName name) { ... } // full list
```

Critical for `Set-Cookie` (one cookie per line, *not* comma-joined), `WWW-Authenticate` (one challenge per line), `Via`. Our `Headers` should support both.

---

## 8. `HttpResponseException` carrying retryability

```java
// Azure SDK
public class HttpResponseException extends CoreException {
    private final boolean isRetryable;

    public HttpResponseException(String message, Response<BinaryData> response, Object value) {
        super(message, null);
        this.value = value;
        this.response = response;
        this.isRetryable = response == null || RetryUtils.isRetryable(response.getStatusCode());
    }

    @Override public boolean isRetryable() { return isRetryable; }
}
```

The exception itself knows whether retry is appropriate. The retry policy asks `exception.isRetryable()` instead of re-classifying via status codes. Cleaner separation than a separate `Predicate<HttpRetryCondition>`.

Shape we'd want:

```kotlin
// proposed
open class HttpResponseException(
    message: String,
    val response: Response,
    val isRetryable: Boolean = response.status.code in retryableStatusCodes,
    cause: Throwable? = null,
) : IOException(message, cause) {
    companion object {
        private val retryableStatusCodes = (setOf(408, 429) + (500..599)) - setOf(501, 505)
    }
}
```

---

## 9. Server-Sent Events

The SSE spec's `data:` field can repeat — `data: line1\ndata: line2\n\n` produces a single event with two data lines. Azure models that explicitly:

```java
// Azure SDK
public final class ServerSentEvent {
    private String id;
    private String event;
    private List<String> data;     // multi-line data field
    private String comment;
    private Duration retryAfter;
}
```

And listener with default error/close hooks:

```java
// Azure SDK
@FunctionalInterface
public interface ServerSentEventListener {
    void onEvent(ServerSentEvent sse) throws IOException;
    default void onError(Throwable throwable) { }
    default void onClose()                    { }
}
```

Shape we'd want on top of our existing `BufferedSource`:

```kotlin
// proposed
data class ServerSentEvent(
    val id: String? = null,
    val event: String? = null,
    val data: List<String> = emptyList(),
    val comment: String? = null,
    val retry: Duration? = null,
)

class ServerSentEventReader(private val source: BufferedSource) {
    /** Returns the next event, or null at stream end. */
    fun next(): ServerSentEvent? {
        if (source.exhausted()) return null
        // parse per https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation
        // — accumulate data: lines, terminator blank line emits the event
    }
}

fun BufferedSource.readServerSentEvents(): Sequence<ServerSentEvent> =
    generateSequence { ServerSentEventReader(this).next() }
```

Coroutine `Flow` adapter could live in a separate module.

---

## 10. Pagination

```java
// Azure SDK — PagedResponse
public final class PagedResponse<T> extends Response<List<T>> {
    private final String continuationToken;
    private final String nextLink;
    private final String previousLink;
    private final String firstLink;
    private final String lastLink;
}

// Azure SDK — PagingOptions
public final class PagingOptions {
    private Long offset;
    private Long pageSize;
    private Long pageIndex;
    private String continuationToken;
}
```

Different APIs page differently — token-based (continuationToken), URL-based (HATEOAS nextLink), or offset/index-based. Azure's iterator handles all three:

```java
// Azure SDK — PagedIterable.PagedIterator
private void receivePage(PagedResponse<T> page) {
    addPage(page);
    nextLink = page.getNextLink();
    continuationToken = page.getContinuationToken();
    this.done = (nextLink == null || nextLink.isEmpty())
        && (continuationToken == null || continuationToken.isEmpty());
}
```

Two iteration modes — flatten items, or iterate one page at a time:

```java
// Azure SDK — PagedIterable
public Iterator<T> iterator()                                 { return iterableByItemInternal(null).iterator(); }
public Iterable<PagedResponse<T>> iterableByPage()            { return iterableByPageInternal(null); }
public Stream<T> stream()                                     { ... }
public Stream<PagedResponse<T>> streamByPage()                { ... }
```

We'd want a Kotlin sequence-based equivalent:

```kotlin
// proposed
class PagedIterable<T>(
    private val firstPage: (PagingOptions) -> PagedResponse<T>,
    private val nextPage: (PagingOptions, String) -> PagedResponse<T>?,   // null = no more
) : Iterable<T> {
    fun byPage(options: PagingOptions = PagingOptions()): Sequence<PagedResponse<T>> = sequence {
        var page: PagedResponse<T>? = firstPage(options)
        while (page != null) {
            yield(page)
            val link = page.nextLink ?: page.continuationToken ?: break
            page = nextPage(options, link)
        }
    }

    override fun iterator(): Iterator<T> = byPage().flatMap { it.value.asSequence() }.iterator()
}
```

---

## 11. Observability

### 11.1 `ClientLogger` — structured logging facade

```java
// Azure SDK — usage
logger.atVerbose()
    .addKeyValue(HTTP_REQUEST_METHOD_KEY, request.getHttpMethod())
    .addKeyValue(URL_FULL_KEY, redactedUrl)
    .addKeyValue(HTTP_REQUEST_BODY_SIZE_KEY, requestContentLength)
    .setEventName(HTTP_REQUEST_EVENT_NAME)
    .setInstrumentationContext(context)
    .log();
```

The implementation has a critical no-allocation fast path for disabled levels:

```java
// Azure SDK — LoggingEvent
private static final LoggingEvent NOOP = new LoggingEvent(null, null, null, false);

static LoggingEvent create(Slf4jLoggerShim logger, LogLevel level, Map<String, Object> globalContext) {
    if (logger.canLogAtLevel(level)) {
        return new LoggingEvent(logger, level, globalContext, true);
    }
    return NOOP;   // shared singleton; addKeyValue/setEventName/log are no-ops
}

public LoggingEvent addKeyValue(String key, String value) {
    if (this.isEnabled) {
        addKeyValueInternal(key, value);
    }
    return this;
}
```

When verbose is disabled, `logger.atVerbose().addKeyValue(...).addKeyValue(...).log()` allocates zero objects. We use raw SLF4J today — same level checking but no structured key/value mechanism.

We'd want:

```kotlin
// proposed
class ClientLogger(private val name: String, private val globalContext: Map<String, Any?> = emptyMap()) {
    fun atError(): LoggingEvent   = LoggingEvent.create(this, LogLevel.ERROR)
    fun atWarning(): LoggingEvent = LoggingEvent.create(this, LogLevel.WARNING)
    fun atInfo(): LoggingEvent    = LoggingEvent.create(this, LogLevel.INFO)
    fun atVerbose(): LoggingEvent = LoggingEvent.create(this, LogLevel.VERBOSE)
}

class LoggingEvent internal constructor(...) {
    fun field(key: String, value: Any?): LoggingEvent { ... }
    fun event(name: String): LoggingEvent { ... }
    fun cause(t: Throwable): LoggingEvent { ... }
    fun log(message: String = "") { ... }
    companion object {
        val NOOP = LoggingEvent(...)
        fun create(logger: ClientLogger, level: LogLevel): LoggingEvent { ... }
    }
}
```

Backs onto SLF4J 2.x's `KeyValuePair` API — no new runtime dep.

### 11.2 `UrlRedactor`

Azure has a `UrlRedactionUtil.getRedactedUri(URI uri, Set<String> allowedQueryParams)`. The instrumentation policy uses it on every URL it logs. Without it, every `LoggableRequestBody` snapshot is a secret-leak waiting to happen (think SAS tokens, API keys passed via query string).

Settings on `HttpInstrumentationOptions`:

```java
// Azure SDK
private static final List<String> DEFAULT_QUERY_PARAMS_ALLOWLIST = Collections.singletonList("api-version");
private Set<String> allowedQueryParamNames;
private Set<HttpHeaderName> allowedHeaderNames;
private boolean isRedactedHeaderNamesLoggingEnabled;   // true: log "REDACTED" for excluded; false: omit entirely
```

We'd want the same allow-list pattern, configurable on whatever options class wires the instrumentation policy.

### 11.3 `Tracer` / `Span` / `TracingScope`

```java
// Azure SDK — Tracer
public interface Tracer {
    SpanBuilder spanBuilder(String spanName, SpanKind spanKind, InstrumentationContext instrumentationContext);
    default boolean isEnabled() { return false; }
}

// usage
Span span = tracer.spanBuilder("operationName", SpanKind.CLIENT, instrumentationContext).startSpan();
try (TracingScope scope = span.makeCurrent()) {
    clientCall(childContext).close();
} catch (Throwable t) {
    span.end(getCause(t));
    throw t;
} finally {
    span.end();
}
```

OpenTelemetry semantic conventions; `SpanKind.CLIENT/SERVER/PRODUCER/CONSUMER/INTERNAL`. We have `Span`/`TracingScope`/`InstrumentationContext` abstractions in our Kotlin tree — but no concrete implementation. The OpenTelemetry integration sits in `implementation/instrumentation/otel/` in their tree. Their pattern: abstract interfaces in `instrumentation/`, OTel-coupled implementation in `implementation/instrumentation/otel/`. Mirrors our `IoProvider` pattern — and we should follow the same separation.

---

## 12. Configuration + proxy support

### 12.1 Layered configuration

```java
// Azure SDK
public final class Configuration {
    public static Configuration getGlobalConfiguration() { ... }
    public String get(String name) { ... }                  // env var > system property > default
    public static final String MAX_RETRY_ATTEMPTS = "...";
    public static final String LOG_LEVEL = "...";
    public static final String HTTP_CLIENT_IMPLEMENTATION = "...";
    // etc.
}
```

Used everywhere — `HttpRetryPolicy` reads `MAX_RETRY_ATTEMPTS`, `UserAgentPolicy` reads `java.version`/`os.name`/`os.version`, `HttpPipelineBuilder` reads `AZURE_HTTP_CLIENT_SHARING`, `HttpInstrumentationOptions` reads `LOG_LEVEL`.

We have nothing. Every default is currently hard-coded. For production SDKs, users need to tune behavior via env vars / system properties without code changes.

### 12.2 Proxy support

```java
// Azure SDK — ProxyOptions
private static final String JAVA_PROXY_HOST = "proxyHost";
private static final String JAVA_PROXY_PORT = "proxyPort";
private static final String JAVA_PROXY_USER = "proxyUser";
private static final String JAVA_PROXY_PASSWORD = "proxyPassword";
private static final String JAVA_NON_PROXY_HOSTS = "http.nonProxyHosts";

// Honors HTTPS_PROXY / HTTP_PROXY / NO_PROXY env vars in addition.
// Pattern matching for non-proxy hosts with backslash escapes.
private static final Pattern HTTP_NON_PROXY_HOSTS_SPLIT = Pattern.compile("(?<!\\\\)\\|");
private static final Pattern NO_PROXY_SPLIT             = Pattern.compile("(?<!\\\\),");

// Plus ChallengeHandler for proxy authentication.
```

Enterprise / corporate deployments without this typically can't use the SDK at all. Major feature for any SDK targeting enterprise customers.

---

## 13. Annotation-based interface clients (Retrofit/Azure paradigm)

This is an *architectural* choice, not a feature. Worth flagging because it changes how every service gets authored.

```java
// Azure SDK — usage
@HttpRequestInformation(method = HttpMethod.PUT,
    path = "subscriptions/{subscriptionId}/resourceGroups/{rgName}/providers/Microsoft.Compute/virtualMachines/{vmName}",
    expectedStatusCodes = {200})
@UnexpectedResponseExceptionDetail(statusCode = {404}, exceptionBodyClass = MyCustomError.class)
Response<VirtualMachine> createOrUpdate(
    @PathParam("subscriptionId") String subscriptionId,
    @PathParam("rgName") String resourceGroupName,
    @PathParam("vmName") String vmName,
    @HeaderParam("If-Match") String ifMatch,
    @BodyParam("application/json") RequestBody body
);
```

And a great expansion trick:

```java
// Azure SDK — usage
@HeaderParam("x-ms-meta-")
Response<Void> setMetadata(@PathParam("container") String container,
                            @PathParam("blob") String blob,
                            @HeaderParam("x-ms-meta-") Map<String, String> metadata);
//  → expands to N headers: "x-ms-meta-<entryKey>: <entryValue>"
```

A code generator scans the interfaces and emits `HttpRequest`-building code. Trade-off:

| | Hand-written clients (our current model) | Annotation-based generated clients |
|---|---|---|
| Surface area scaling | Linear in author effort | One generator handles N services |
| Type-safety | Full — Kotlin types throughout | Same — annotations are processed at compile time |
| Build complexity | Zero | Need an annotation processor or KSP module |
| Debugging | Direct — step into your code | Indirect — step into generated code |
| Run-time reflection cost | Zero | Zero (KSP generates code, no reflection) |
| Suitable for | Tens of endpoints | Hundreds of endpoints, especially auto-generated from OpenAPI / TypeSpec |

This is a big commitment. Worth a separate design discussion before starting. **My recommendation: defer until we know the target service shape.** If we're building one or two SDKs with focused APIs, hand-written wins on simplicity. If we're building a generated SDK from OpenAPI specs, the annotation processor is the right play.

---

## 14. Small utilities

### 14.1 `CoreUtils.randomUuid()` — avoid `SecureRandom` blocking

```java
// Azure SDK
public static UUID randomUuid() {
    return randomUuid(ThreadLocalRandom.current().nextLong(), ThreadLocalRandom.current().nextLong());
}

static UUID randomUuid(long msb, long lsb) {
    msb &= 0xffffffffffff0fffL; // Clear UUID version
    msb |= 0x0000000000004000L; // Set version 4
    lsb &= 0x3fffffffffffffffL; // Clear variant
    lsb |= 0x8000000000000000L; // Set IETF variant
    return new UUID(msb, lsb);
}
```

`UUID.randomUUID()` uses `SecureRandom` which can block on `/dev/random` under load. For request IDs and correlation IDs (high-frequency, non-cryptographic use), this is a known JDK trap. Reactor BlockHound flags it. The Azure pattern uses `ThreadLocalRandom` with manual bit-fiddling to produce a valid v4 UUID without the blocking source.

Trivial to copy:

```kotlin
// proposed
internal object Uuids {
    /** Type-4 UUID using ThreadLocalRandom; non-cryptographic but non-blocking. */
    fun random(): UUID {
        val tlr = ThreadLocalRandom.current()
        var msb = tlr.nextLong()
        var lsb = tlr.nextLong()
        msb = msb and 0xffffffffffff0fffL.toLong() or 0x0000000000004000L
        lsb = lsb and 0x3fffffffffffffffL or Long.MIN_VALUE   // 0x8000000000000000L
        return UUID(msb, lsb)
    }
}
```

Use in `RequestIdPolicy` and `InstrumentationContext` trace-id generation.

### 14.2 `ExpandableEnum<T>`

```java
// Azure SDK
public interface ExpandableEnum<T> { T getValue(); }
```

A marker for "type-safe extensible enum-like constants" — `HttpHeaderName`, `HttpPipelinePosition`, `SpanKind`. The pattern: a `ConcurrentHashMap` for canonicalization, public static final instances for known values, `fromString(...)` for new values. We have Kotlin enums for closed sets but no clean pattern for open sets. Worth establishing.

### 14.3 `SetDatePolicy` + `DateTimeRfc1123`

```java
// Azure SDK
public class SetDatePolicy implements HttpPipelinePolicy {
    private static final DateTimeFormatter FORMATTER
        = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'").withZone(ZoneOffset.UTC).withLocale(Locale.US);

    @Override public Response<BinaryData> process(HttpRequest httpRequest, HttpPipelineNextPolicy next) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            httpRequest.getHeaders().set(HttpHeaderName.DATE, DateTimeRfc1123.toRfc1123String(now));
        } catch (IllegalArgumentException ignored) {
            httpRequest.getHeaders().set(HttpHeaderName.DATE, FORMATTER.format(now));
        }
        return next.process();
    }
}
```

Some APIs (S3, signed-URL services) require a `Date` header. RFC 1123 format is the standard. `DateTimeRfc1123` handles both parsing (for `Retry-After` headers, `Last-Modified` responses) and emit.

---

## 15. Prioritized recommendations

Updated ranking based on this deeper read:

| Rank | Item | Rationale |
|---|---|---|
| **0** | **Rebuild `RequestPipeline` with positions + pillars + `next.copy()`** | Structural prerequisite for retry, redirect, auth, instrumentation policies. Without `next.copy()`, stateful policies are unimplementable. |
| 1 | `UrlRedactor` with allow-list pattern | Required before any URL logging. Prevents credential leaks. |
| 2 | `ClientLogger` structured-logging facade with no-alloc disabled path | Needed by all the policies for consistent event names + key/value fields. |
| 3 | `HttpInstrumentationPolicy` wiring up `LoggableRequestBody`/`LoggableResponseBody` | Makes our existing logging-body work actually visible end-to-end. |
| 4 | `HttpRetryPolicy` with `Retry-After` parsing + jitter + exception-chain walk + suppressed accumulation | Most-requested production feature. Status code retry classification: 408, 429, 5xx except 501/505. |
| 5 | `HttpRedirectPolicy` with `Authorization` stripping + URI-loop detection | Security-critical defensive measures. 301/302/307/308 (consider 303 separately). |
| 6 | HTTPS-only enforcement on credential policies + `Credential` hierarchy | Fail-fast principle applied to credentials. Compile-time-safe credential injection. |
| 7 | `HttpResponseException` carrying `isRetryable` | Cleaner exception classification than separate predicates. |
| 8 | Audit `Headers.kt` for interning + case-insensitive identity + multi-value | Likely a correctness bug if not already done. Cheap to fix. |
| 9 | `Configuration` system (env vars + system properties with defaults) | Runtime tunables. Read `MAX_RETRY_ATTEMPTS`, `LOG_LEVEL`, etc. |
| 10 | `HttpRange`, `ETag`, `RequestConditions` typed values | Small, high-value type safety wins. |
| 11 | `BufferedSource.slice(offset, count)` | Useful for partial uploads, multipart, range responses. |
| 12 | `WWW-Authenticate` ChallengeHandler (Basic + Digest) | Required for enterprise / on-prem APIs. Skip if target is modern bearer-token APIs only. |
| 13 | Memory-mapped `FileRequestBody.toByteBuffer()` | Local hashing / signing without heap copy. |
| 14 | `ServerSentEventReader` with `data: List<String>` per spec | If LLM/streaming APIs are a target. |
| 15 | `PagedIterable<T>` with HATEOAS + continuation token + offset | If list-heavy services are a target. |
| 16 | `ProxyOptions` with system-property / env-var integration | Enterprise deployment requirement. |
| 17 | `randomUuid()` without `SecureRandom` blocking | Cheap trap-avoidance fix. |
| 18 | `SetDatePolicy` + `DateTimeRfc1123` | Required for some signing schemes. |
| 19 | Annotation-based interface clients (Retrofit/Azure paradigm) | Big architectural commitment. Defer until target service shape is known. |

---

## 16. What we should *not* adopt

- **`BinaryData` unification**. Our split `RequestBody` (sink-writer) / `ResponseBody` (source-provider) is asymmetric on purpose — it mirrors wire-protocol asymmetry. Azure's unification forces eight subclasses to each implement five accessors. Their elegance trades complexity for symmetry; ours trades symmetry for narrower contracts.
- **Embedded Jackson Core**. Azure ships JSON parsing inline. We keep `Serde` abstract in core. Concrete JSON impl belongs in an optional extension module.
- **Embedded Aalto XML**. Same reasoning. Niche need; users who want XML opt into a module.
- **Full OpenTelemetry SDK dependency**. We have abstract `Tracer`/`Span`/`TracingScope` interfaces and that's enough for the core. The OTel-coupled implementation goes in a separate adapter module — same pattern as `sdk-io-okio3`.
- **`AccessibleByteArrayOutputStream`-style unsafe array return**. Useful for byte-array-based collections; irrelevant for our segment-pool model.
- **`AtomicReferenceFieldUpdater` everywhere**. Saves ~20ns per first-access compared to a `ReentrantLock`. Nobody will measure it.

---

## 17. Suggested next two days of work

Concrete sequence that delivers a complete end-to-end feature:

**Day 1 — pipeline foundation**

1. Define `HttpPipelinePosition` with the same pillar constants (REDIRECT, RETRY, AUTHENTICATION, INSTRUMENTATION) and user-visible slots (BEFORE_REDIRECT, AFTER_REDIRECT, AFTER_RETRY, AFTER_AUTHENTICATION, AFTER_INSTRUMENTATION).
2. Rewrite `HttpPipelinePolicy` as a `fun interface` with `process(request, next): Response` and a default `getPipelinePosition() = AFTER_RETRY`.
3. Build `HttpPipelineNextPolicy` with `process()` and `copy()`.
4. Build `HttpPipelineBuilder` with bucket fields per position, pillar singletons with last-wins-with-warning behavior, and an `addPolicy(policy)` that routes by `getPipelinePosition()`.
5. Implement `HttpPipelineCallState` (or equivalent) tracking the current policy index.

**Day 2 — safe HTTP logging end-to-end**

1. `UrlRedactor` with `Set<String>` allow-list and default of `["api-version"]` (or empty).
2. `ClientLogger` facade with `atError/atWarning/atInfo/atVerbose`, `field(k, v)`, `event(name)`, `cause(t)`, `log(msg)`. The `LoggingEvent.NOOP` singleton for disabled levels.
3. `HttpInstrumentationPolicy` (pillar = INSTRUMENTATION) that:
   - Wraps the request body in `LoggableRequestBody` if logging enabled
   - Wraps the response body in `LoggableResponseBody` if logging enabled
   - Emits `http.request` and `http.response` structured events
   - Uses the redactor for URLs
   - Allow-lists headers and query params (default set ~20 standard headers).
4. End-to-end test: install a pipeline with `HttpInstrumentationPolicy`, send a request with a URL containing a secret query param, assert the captured log event has the secret redacted to `***`, body bytes captured, no token leakage.

That gets to a real, usable, secure logging story. After that, `HttpRetryPolicy` with `Retry-After` parsing is a natural Day 3 — it uses `next.copy()` from Day 1's foundation and emits events through Day 2's logger.

Everything else from rank 5+ can be sequenced freely after that point.
