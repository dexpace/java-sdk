package org.dexpace.sdk.core.http.response;

import org.dexpace.sdk.core.http.common.MediaType;
import org.dexpace.sdk.core.http.common.Protocol;
import org.dexpace.sdk.core.http.request.Method;
import org.dexpace.sdk.core.http.request.Request;
import org.dexpace.sdk.core.io.BufferedSource;
import org.dexpace.sdk.core.io.Io;
import org.dexpace.sdk.io.OkioIoProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Exercises the {@code @JvmStatic} forwarder methods that Kotlin emits on the {@code Response}
 * class (not the {@code Companion}). Java syntax dispatches to the class-level static stubs,
 * so this test class produces coverage for the stubs that Kotlin tests would otherwise miss.
 */
final class ResponseBodyJavaTest {

    @BeforeAll
    static void installProvider() {
        Io.INSTANCE.installProvider(OkioIoProvider.INSTANCE);
    }

    @Test
    void responseBuilderViaJavaStatic() throws Exception {
        Request request = Request.builder()
                .url("https://api.example.test")
                .method(Method.GET)
                .build();
        Response response = Response.builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .status(Status.OK)
                .build();
        assertNotNull(response);
        assertEquals(Status.OK, response.getStatus());
    }

    @Test
    void responseBodyCreateViaJavaStatic() {
        BufferedSource source = Io.INSTANCE.getProvider().source("hi".getBytes());
        MediaType type = MediaType.parse("text/plain");
        ResponseBody body = ResponseBody.create(source, type, 2L);
        assertNotNull(body);
        assertEquals(2L, body.contentLength());
        assertEquals(type, body.mediaType());
    }
}
