/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.shrinktest

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.dexpace.sdk.core.http.common.CommonMediaTypes
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestBody
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.serde.Tristate
import org.dexpace.sdk.io.OkioIoProvider
import org.dexpace.sdk.serde.jackson.JacksonSerde
import org.dexpace.sdk.transport.okhttp.OkHttpTransport

/**
 * A small but representative SDK consumer, used as the input program for the R8 shrink-survival
 * check. It deliberately drives the toolkit only through its documented public surface and SPI
 * seams — the same surface the shipped `META-INF/proguard` consumer rules protect — and prints
 * [SUCCESS_SENTINEL] on a clean run.
 *
 * The harness runs this from the R8-shrunk jar. Because that run performs a real HTTP round-trip
 * and a real JSON round-trip, it proves not merely that the kept classes still exist but that their
 * members remain wired correctly after tree-shaking and (potential) renaming.
 */
public object ShrinkSurvivalApp {
    /** Printed verbatim to stdout once every exercise below has passed. */
    public const val SUCCESS_SENTINEL: String = "SHRINK-SURVIVAL-OK"

    @JvmStatic
    public fun main(args: Array<String>) {
        // 1. I/O provider seam — install the only adapter through the documented entry point.
        Io.installProvider(OkioIoProvider)

        exerciseTransportRoundTrip()
        exerciseSerdeRoundTrip()

        // Reaching here means every kept surface resolved and behaved. Emit the sentinel the
        // harness greps for.
        println(SUCCESS_SENTINEL)
    }

    /**
     * Drives a full request/response exchange through [OkHttpTransport] against an in-process
     * [MockWebServer]. Exercises the request builder, [RequestBody], the transport's sync
     * `execute` path, and reading the [org.dexpace.sdk.core.http.response.Response] body via the
     * I/O seam.
     */
    private fun exerciseTransportRoundTrip() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(Status.OK.code)
                    .addHeader("Content-Type", "application/json")
                    .body("""{"echo":"pong"}""")
                    .build(),
            )
            server.start()

            val baseUrl = server.url("/echo").toString()

            OkHttpTransport.builder().build().use { transport ->
                val request =
                    Request.builder()
                        .method(Method.POST)
                        .url(baseUrl)
                        .addHeader("Accept", "application/json")
                        .body(
                            RequestBody.create(
                                """{"ping":"ping"}""",
                                CommonMediaTypes.APPLICATION_JSON,
                            ),
                        )
                        .build()

                val response = transport.execute(request)
                val status = response.status.code
                val payload = response.body?.source()?.readUtf8().orEmpty()
                response.close()

                check(status == Status.OK.code) { "unexpected status from transport: $status" }
                check(payload.contains("pong")) { "unexpected body from transport: $payload" }
            }
        }
    }

    /**
     * Round-trips a model carrying a [Tristate] field through [JacksonSerde]. This is the most
     * shrink-fragile path: Jackson binds the model reflectively and the custom Tristate module
     * branches on the runtime type of each variant, so a shrinker that stripped the Tristate
     * hierarchy or the data-class metadata would surface here.
     */
    private fun exerciseSerdeRoundTrip() {
        val serde = JacksonSerde.withDefaults()

        val original =
            ConsumerModel(
                name = "widget",
                replacement = Tristate.present("gadget"),
                cleared = Tristate.nullValue(),
                untouched = Tristate.absent(),
            )

        // Drive the round-trip through the core Serde SPI (Serializer / Deserializer) rather than a
        // Jackson-specific helper, so the consumer touches only sdk-core's serde surface. Jackson
        // still recovers each field's declared type (including Tristate<String>) from the model's
        // Kotlin metadata, which is exactly the reflective path the shipped keep-rules protect.
        val json = serde.serializer.serialize(original)
        val restored = serde.deserializer.deserialize(json, ConsumerModel::class.java)

        check(restored.name == "widget") { "name did not survive serde round-trip: ${restored.name}" }

        // Present carries its payload across the wire.
        val replacement = restored.replacement
        check(replacement is Tristate.Present && replacement.value == "gadget") {
            "present Tristate did not survive serde round-trip: $replacement"
        }
        // An explicit JSON null stays Null.
        check(restored.cleared is Tristate.Null) {
            "null Tristate did not survive serde round-trip: ${restored.cleared}"
        }
        // Absent is omitted on serialize; on deserialize the model's Tristate.Absent default keeps
        // the missing key Absent rather than collapsing it to Null. Exercising all three variants
        // forces every branch of the custom Tristate (de)serializer the keep-rules protect.
        check(restored.untouched is Tristate.Absent) {
            "absent Tristate did not survive serde round-trip: ${restored.untouched}"
        }
    }
}

/**
 * Stand-in for an application model that a consumer would (de)serialize with the SDK's Jackson
 * serde. The [Tristate] fields force the custom module's reflective binding into play. Each
 * defaults to [Tristate.Absent] so that a JSON key missing entirely deserializes as Absent rather
 * than collapsing to Null via Jackson's missing-property handling — the contract documented on the
 * SDK's own Tristate module.
 */
public data class ConsumerModel(
    val name: String,
    val replacement: Tristate<String> = Tristate.absent(),
    val cleared: Tristate<String> = Tristate.absent(),
    val untouched: Tristate<String> = Tristate.absent(),
)
