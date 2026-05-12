package org.dexpace.sdk.core.http.request;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.dexpace.sdk.core.http.common.MediaType;
import org.dexpace.sdk.core.io.Buffer;
import org.dexpace.sdk.core.io.BufferedSource;
import org.dexpace.sdk.core.io.Io;
import org.dexpace.sdk.io.OkioIoProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the {@code @JvmStatic} forwarder methods that Kotlin emits on
 * {@link RequestBody} (the *class*, not the {@code Companion}) so coverage tools see
 * the class-level static stubs reached from Java syntax.
 */
final class RequestBodyJavaTest {

    @BeforeAll
    static void installProvider() {
        Io.INSTANCE.installProvider(OkioIoProvider.INSTANCE);
    }

    @Test
    void createFromByteArrayCallsClassLevelStatic() {
        byte[] bytes = "hi".getBytes();
        RequestBody body = RequestBody.create(bytes, MediaType.parse("text/plain"));
        assertNotNull(body);
        assertEquals(2L, body.contentLength());
        assertTrue(body.isReplayable());
    }

    @Test
    void createFromStringCallsClassLevelStatic() {
        RequestBody body = RequestBody.create("hi", MediaType.parse("text/plain"));
        assertNotNull(body);
        assertEquals(2L, body.contentLength());
    }

    @Test
    void createFromBufferCallsClassLevelStatic() throws java.io.IOException {
        Buffer buffer = Io.INSTANCE.getProvider().buffer();
        buffer.write("abc".getBytes());
        RequestBody body = RequestBody.create(buffer);
        assertEquals(3L, body.contentLength());
    }

    @Test
    void createFromBufferedSourceCallsClassLevelStatic() {
        BufferedSource source = Io.INSTANCE.getProvider().source("hi".getBytes());
        RequestBody body = RequestBody.create(source);
        assertEquals(-1L, body.contentLength());
    }

    @Test
    void createFromInputStreamCallsClassLevelStatic() {
        InputStream input = new ByteArrayInputStream("hi".getBytes());
        RequestBody body = RequestBody.create(input, 2L);
        assertEquals(2L, body.contentLength());
    }

    @Test
    void createFromFormDataCallsClassLevelStatic() {
        RequestBody body = RequestBody.create(Collections.singletonMap("k", "v"));
        assertEquals(3L, body.contentLength());
    }

    @Test
    void createFromFilePathCallsClassLevelStatic() throws IOException {
        Path file = Files.createTempFile("rb-java", ".bin");
        try {
            Files.write(file, "hi".getBytes());
            RequestBody body = RequestBody.create(file);
            assertEquals(2L, body.contentLength());
            assertTrue(body.isReplayable());
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
