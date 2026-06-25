/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.example

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.auth.KeyCredential
import org.dexpace.sdk.core.http.common.CommonMediaTypes
import org.dexpace.sdk.core.http.common.HttpHeaderName
import org.dexpace.sdk.core.http.pipeline.HttpPipeline
import org.dexpace.sdk.core.http.pipeline.HttpPipelineBuilder
import org.dexpace.sdk.core.http.pipeline.steps.DefaultInstrumentationStep
import org.dexpace.sdk.core.http.pipeline.steps.DefaultRedirectStep
import org.dexpace.sdk.core.http.pipeline.steps.DefaultRetryStep
import org.dexpace.sdk.core.http.pipeline.steps.HttpInstrumentationOptions
import org.dexpace.sdk.core.http.pipeline.steps.HttpLogLevel
import org.dexpace.sdk.core.http.pipeline.steps.KeyCredentialAuthStep
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestBody
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.serde.deserialize
import org.dexpace.sdk.io.OkioIoProvider
import org.dexpace.sdk.serde.jackson.JacksonSerde
import org.dexpace.sdk.transport.okhttp.OkHttpTransport
import java.net.URL

/*
 * End-to-end usage sample for the dexpace SDK.
 *
 * This module exists as an executable smoke test of the assembled toolkit: it wires the four
 * pluggable seams together and proves they cooperate over a real HTTP exchange —
 *
 *   - an OkioIoProvider installed into the Io seam,
 *   - the OkHttpTransport as the terminal HttpClient,
 *   - an HttpPipeline carrying one step per user-installable pillar (REDIRECT, RETRY, AUTH,
 *     LOGGING),
 *   - and JacksonSerde for typed request/response bodies.
 *
 * The request targets an embedded MockWebServer, so the sample is fully deterministic and needs
 * no network access — `./gradlew :sdk-example:run` produces the same output everywhere.
 *
 * The AUTH pillar refuses to stamp credentials over plaintext HTTP, so the embedded server speaks
 * HTTPS with a self-signed certificate (newTlsServer) and the transport is configured to trust it
 * (TlsServer.newTransportTrusting) — the sample uses TLS exactly as a production caller would.
 *
 * The wiring is deliberately split out of main() into small functions so the smoke test can
 * exercise the exact same code paths the sample runs.
 */

/** HTTP 201 Created — the status the embedded server returns for the sample POST. */
private const val HTTP_CREATED = 201

/** A typed request payload, serialized to JSON by the [JacksonSerde]. */
public data class CreateUserRequest(
    val name: String,
    val email: String,
)

/** A typed response payload, deserialized from JSON by the [JacksonSerde]. */
public data class User(
    val id: Long,
    val name: String,
    val email: String,
)

/**
 * Installs the Okio-backed [Io] provider. Install is idempotent for the same provider, so calling
 * this from both [main] and the smoke test is safe.
 */
public fun installIoProvider() {
    Io.installProvider(OkioIoProvider)
}

/**
 * Generates a self-signed certificate for `localhost` and starts an HTTPS [MockWebServer] serving it.
 * The matching client trust material is returned alongside so the caller can build a transport
 * that trusts this exact certificate — see [newTransportTrusting].
 */
public fun newTlsServer(): TlsServer {
    val certificate =
        HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
    val serverCertificates =
        HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
    val clientCertificates =
        HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()

    val server = MockWebServer()
    server.useHttps(serverCertificates.sslSocketFactory())
    return TlsServer(server, clientCertificates)
}

/** An embedded HTTPS [MockWebServer] paired with the client trust material that accepts it. */
public class TlsServer internal constructor(
    public val server: MockWebServer,
    private val clientCertificates: HandshakeCertificates,
) {
    /**
     * Builds an [OkHttpTransport] over a BYO [OkHttpClient] that trusts this server's self-signed
     * certificate. The transport is SDK-managed, so closing it shuts the underlying client down.
     */
    public fun newTransportTrusting(): OkHttpTransport {
        val client =
            OkHttpClient.Builder()
                // Demo only: trusts a single self-signed certificate so the sample needs no
                // network. Production callers should rely on the default system trust store and
                // not configure custom trust material here.
                .sslSocketFactory(
                    clientCertificates.sslSocketFactory(),
                    clientCertificates.trustManager,
                )
                .build()
        return OkHttpTransport.create(client)
    }
}

/**
 * Assembles an [HttpPipeline] over [transport] with exactly one step on each user-installable
 * pillar stage. The SERDE pillar is reserved by the runtime and carries no user step — typed
 * (de)serialization happens explicitly at the call site via [JacksonSerde], as shown in
 * [createUser].
 */
public fun buildPipeline(transport: HttpClient): HttpPipeline =
    HttpPipelineBuilder(transport)
        // REDIRECT pillar — follow 3xx responses within a hop budget.
        .append(DefaultRedirectStep())
        // RETRY pillar — exponential backoff that honours `Retry-After`. This re-sends a request
        // when its method is idempotent or its body is replayable; the sample's POST carries a
        // replayable (in-memory) body, so it qualifies. A real caller retrying a non-idempotent
        // write should pair this with an idempotency key (see `IdempotencyKeyStep`) so a retried
        // POST cannot create a duplicate server-side.
        .append(DefaultRetryStep())
        // AUTH pillar — stamp a static API key into the `Authorization` header.
        .append(
            KeyCredentialAuthStep(
                KeyCredential(
                    apiKey = "example-api-key",
                    headerName = HttpHeaderName.AUTHORIZATION,
                    prefix = "Bearer",
                ),
            ),
        )
        // LOGGING pillar — emit request/response diagnostics at header granularity.
        .append(
            DefaultInstrumentationStep(
                HttpInstrumentationOptions(logLevel = HttpLogLevel.HEADERS),
            ),
        )
        .build()

/**
 * Serializes [request] to a JSON body, POSTs it through [pipeline] to [endpoint], and deserializes
 * the JSON response into a typed [User]. Throws if the server does not answer with a 2xx status.
 *
 * The returned [User] is fully materialized before the response is closed, so the caller does not
 * own any streaming resource.
 */
public fun createUser(
    pipeline: HttpPipeline,
    serde: JacksonSerde,
    endpoint: URL,
    request: CreateUserRequest,
): User {
    val json = serde.serializer.serialize(request)
    val httpRequest =
        Request.builder()
            .method(Method.POST)
            .url(endpoint)
            .addHeader(HttpHeaderName.ACCEPT.toString(), CommonMediaTypes.APPLICATION_JSON.toString())
            .body(RequestBody.create(json, CommonMediaTypes.APPLICATION_JSON))
            .build()

    pipeline.send(httpRequest).use { response ->
        val status = response.status
        val payload = response.body?.source()?.readUtf8().orEmpty()
        check(status.isSuccess) { "Unexpected status $status — body: $payload" }
        return serde.deserializer.deserialize<User>(payload)
    }
}

/**
 * Runs the full sample against an embedded HTTPS [MockWebServer] and prints the typed round-trip.
 *
 * No arguments are read; nothing touches the network. The single canned response makes the output
 * stable across runs and machines.
 */
public fun main() {
    installIoProvider()
    val serde = JacksonSerde.withDefaults()

    // ---- Demo scaffolding: fake the server so the sample is deterministic and network-free. ----
    // Everything in this `tls.server.use { ... }` block stands in for a real backend; a caller
    // wiring the SDK against a live API would not write any of it.
    val tls = newTlsServer()
    tls.server.use { server ->
        // Canned JSON the SDK will deserialize back into a typed `User`.
        server.enqueue(
            MockResponse.Builder()
                .code(HTTP_CREATED)
                .addHeader(
                    HttpHeaderName.CONTENT_TYPE.toString(),
                    CommonMediaTypes.APPLICATION_JSON.toString(),
                )
                .body("""{"id":1,"name":"Ada Lovelace","email":"ada@example.org"}""")
                .build(),
        )
        server.start()

        // ---- SDK wiring: this is the copyable part a real caller would actually write. ----
        tls.newTransportTrusting().use { transport ->
            val pipeline = buildPipeline(transport)
            val endpoint = server.url("/v1/users").toUrl()
            val payload = CreateUserRequest(name = "Ada Lovelace", email = "ada@example.org")

            println("POST $endpoint")
            println("  request : $payload")
            val user = createUser(pipeline, serde, endpoint, payload)
            println("  response: $user")
            println("Created user #${user.id} (${user.name}).")
        }
    }
}
