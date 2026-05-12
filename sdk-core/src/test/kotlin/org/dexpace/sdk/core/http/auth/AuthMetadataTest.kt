package org.dexpace.sdk.core.http.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AuthMetadataTest {

    @Test
    fun `single-scheme constructor populates schemes and uses empty defaults for oauth fields`() {
        val metadata = AuthMetadata(listOf(AuthScheme.BASIC))
        assertEquals(listOf(AuthScheme.BASIC), metadata.schemes)
        assertTrue(metadata.oauthScopes.isEmpty())
        assertTrue(metadata.oauthParams.isEmpty())
    }

    @Test
    fun `multi-scheme constructor preserves scheme order`() {
        val schemes = listOf(AuthScheme.DIGEST, AuthScheme.BASIC, AuthScheme.OAUTH2)
        val metadata = AuthMetadata(schemes)
        assertEquals(schemes, metadata.schemes)
    }

    @Test
    fun `constructor accepts oauth scopes and params`() {
        val scopes = listOf("read", "write")
        val params = mapOf<String, Any>("claims" to "{\"id_token\":{\"email\":null}}")
        val metadata = AuthMetadata(listOf(AuthScheme.OAUTH2), scopes, params)
        assertEquals(scopes, metadata.oauthScopes)
        assertEquals(params, metadata.oauthParams)
    }

    @Test
    fun `empty schemes list is rejected`() {
        val ex = assertFailsWith<IllegalArgumentException> { AuthMetadata(emptyList()) }
        assertEquals("schemes must not be empty", ex.message)
    }

    @Test
    fun `empty schemes list with non-default oauth args is also rejected`() {
        // Confirms the require runs before any other init side-effects regardless of
        // how many constructor arguments are populated.
        assertFailsWith<IllegalArgumentException> {
            AuthMetadata(emptyList(), listOf("scope"), mapOf("x" to "y"))
        }
    }

    @Test
    fun `no-auth marker scheme is permitted`() {
        val metadata = AuthMetadata(listOf(AuthScheme.NO_AUTH))
        assertEquals(listOf(AuthScheme.NO_AUTH), metadata.schemes)
    }
}
