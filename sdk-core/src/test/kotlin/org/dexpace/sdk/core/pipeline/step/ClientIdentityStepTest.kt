/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline.step

import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.util.SdkInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClientIdentityStepTest {
    @Test
    fun `default step on a request with no User-Agent sets dexpace-sdk and jvm tokens`() {
        val step = ClientIdentityStep()
        val request = baseRequest()

        val result = step.apply(request)

        val ua = result.headers.get("User-Agent")
        assertNotNull(ua, "Expected User-Agent to be set")
        assertTrue(ua.startsWith("dexpace-sdk/"), "Expected line to start with dexpace-sdk/, got $ua")
        assertTrue(ua.contains(" jvm/"), "Expected ` jvm/` token, got $ua")
        assertEquals("dexpace-sdk/${SdkInfo.sdkVersion} jvm/${SdkInfo.javaVersion}", ua)
    }

    @Test
    fun `append mode prepends caller's User-Agent to the SDK tokens`() {
        val step = ClientIdentityStep()
        val request =
            baseRequest()
                .newBuilder()
                .setHeader("User-Agent", "MyApp/1.0")
                .build()

        val result = step.apply(request)

        assertEquals(
            "MyApp/1.0 dexpace-sdk/${SdkInfo.sdkVersion} jvm/${SdkInfo.javaVersion}",
            result.headers.get("User-Agent"),
        )
    }

    @Test
    fun `replace mode overwrites caller's User-Agent with the SDK tokens`() {
        val step =
            ClientIdentityStep.builder()
                .mode(ClientIdentityStep.Mode.Replace)
                .build()
        val request =
            baseRequest()
                .newBuilder()
                .setHeader("User-Agent", "MyApp/1.0")
                .build()

        val result = step.apply(request)

        assertEquals(
            "dexpace-sdk/${SdkInfo.sdkVersion} jvm/${SdkInfo.javaVersion}",
            result.headers.get("User-Agent"),
        )
    }

    @Test
    fun `custom token list joins tokens with single spaces`() {
        val step =
            ClientIdentityStep.builder()
                .tokens(listOf("foo/1", "bar/2"))
                .build()

        val result = step.apply(baseRequest())

        assertEquals("foo/1 bar/2", result.headers.get("User-Agent"))
    }

    @Test
    fun `custom header name emits the token line on that header instead of User-Agent`() {
        val step =
            ClientIdentityStep.builder()
                .headerName("X-Dexpace-Client")
                .build()

        val result = step.apply(baseRequest())

        assertEquals(
            "dexpace-sdk/${SdkInfo.sdkVersion} jvm/${SdkInfo.javaVersion}",
            result.headers.get("X-Dexpace-Client"),
        )
        assertNull(result.headers.get("User-Agent"))
    }

    @Test
    fun `empty token list is a no-op — request is returned unchanged`() {
        val step = ClientIdentityStep(tokens = emptyList())
        val request =
            baseRequest()
                .newBuilder()
                .setHeader("User-Agent", "MyApp/1.0")
                .build()

        val result = step.apply(request)

        // Same instance (request returned unchanged) and the existing User-Agent untouched.
        assertSame(request, result)
        assertEquals("MyApp/1.0", result.headers.get("User-Agent"))
    }

    @Test
    fun `builder addToken with name and version composes the slash-separated form`() {
        val step =
            ClientIdentityStep.builder()
                .addToken("okhttp", "5.0.0")
                .build()

        val result = step.apply(baseRequest())

        assertEquals("okhttp/5.0.0", result.headers.get("User-Agent"))
    }

    @Test
    fun `builder addToken discards the default seed on first explicit token`() {
        val step =
            ClientIdentityStep.builder()
                .addToken("custom/1")
                .addToken("okhttp", "5.0.0")
                .build()

        val result = step.apply(baseRequest())

        // The default `dexpace-sdk/...` and `jvm/...` tokens must NOT appear once the
        // caller has supplied any explicit token via `addToken`.
        assertEquals("custom/1 okhttp/5.0.0", result.headers.get("User-Agent"))
    }

    @Test
    fun `step is invocable via the RequestPipelineStep execute method`() {
        val step = ClientIdentityStep.builder().tokens(listOf("foo/1")).build()

        val result = step.execute(baseRequest(), DispatchContext.default())

        assertEquals("foo/1", result.headers.get("User-Agent"))
    }

    @Test
    fun `newBuilder round-trips an instance's tokens, header, and mode`() {
        // A-6: a held step can be tweak-copied via newBuilder() without re-stating fields.
        val original =
            ClientIdentityStep.builder()
                .tokens(listOf("foo/1", "bar/2"))
                .headerName("X-Dexpace-Client")
                .mode(ClientIdentityStep.Mode.Replace)
                .build()

        // An unchanged round-trip reproduces identical emitted output.
        val copy = original.newBuilder().build()
        val seeded =
            baseRequest()
                .newBuilder()
                .setHeader("X-Dexpace-Client", "Existing/9")
                .build()
        val originalValue = original.apply(seeded).headers.get("X-Dexpace-Client")
        val copyValue = copy.apply(seeded).headers.get("X-Dexpace-Client")
        assertEquals(originalValue, copyValue)
        // Replace mode preserved: existing value overwritten with the copied token line.
        assertEquals("foo/1 bar/2", copyValue)
    }

    @Test
    fun `newBuilder preserves the original when a derived field is changed`() {
        val original =
            ClientIdentityStep.builder()
                .tokens(listOf("foo/1"))
                .headerName("X-Client")
                .mode(ClientIdentityStep.Mode.Append)
                .build()

        // Derive a copy that flips to Replace; the original must be unaffected.
        val derived = original.newBuilder().mode(ClientIdentityStep.Mode.Replace).build()
        val request =
            baseRequest()
                .newBuilder()
                .setHeader("X-Client", "Caller/2")
                .build()

        assertEquals("Caller/2 foo/1", original.apply(request).headers.get("X-Client"), "Original keeps Append mode")
        assertEquals("foo/1", derived.apply(request).headers.get("X-Client"), "Derived uses Replace mode")
    }

    @Test
    fun `newBuilder treats copied tokens as caller-owned so addToken appends`() {
        val original = ClientIdentityStep.builder().tokens(listOf("foo/1")).build()

        // addToken on a newBuilder() copy must append (no default seed to discard).
        val derived = original.newBuilder().addToken("bar", "2").build()

        assertEquals("foo/1 bar/2", derived.apply(baseRequest()).headers.get("User-Agent"))
    }

    @Test
    fun `newBuilder round-trips the default-seeded tokens`() {
        // A default step round-trips to the same dexpace-sdk + jvm token line.
        val original = ClientIdentityStep()
        val copy = original.newBuilder().build()

        assertEquals(
            original.apply(baseRequest()).headers.get("User-Agent"),
            copy.apply(baseRequest()).headers.get("User-Agent"),
        )
        assertEquals(
            "dexpace-sdk/${SdkInfo.sdkVersion} jvm/${SdkInfo.javaVersion}",
            copy.apply(baseRequest()).headers.get("User-Agent"),
        )
    }

    @Test
    fun `case-insensitive existing User-Agent header is still detected for Append`() {
        // Headers normalise names to lower case; setting `user-agent` and reading `User-Agent`
        // must round-trip so the step's existing-value check picks up the caller's value.
        val step = ClientIdentityStep.builder().tokens(listOf("foo/1")).build()
        val request =
            baseRequest()
                .newBuilder()
                .setHeader("user-agent", "MyApp/1.0")
                .build()

        val result = step.apply(request)

        assertEquals("MyApp/1.0 foo/1", result.headers.get("User-Agent"))
    }

    private fun baseRequest(): Request =
        Request.builder()
            .method(Method.GET)
            .url("https://api.example.com/x")
            .build()
}
