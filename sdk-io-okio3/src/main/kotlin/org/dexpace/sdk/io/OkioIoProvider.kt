package org.dexpace.sdk.io

import okio.buffer
import okio.sink
import okio.source
import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSink
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.IoProvider
import org.dexpace.sdk.core.io.Sink
import org.dexpace.sdk.core.io.Source
import org.dexpace.sdk.io.internal.ForeignSinkAdapter
import org.dexpace.sdk.io.internal.ForeignSourceAdapter
import org.dexpace.sdk.io.internal.OkioBuffer
import org.dexpace.sdk.io.internal.OkioBufferedSink
import org.dexpace.sdk.io.internal.OkioBufferedSource
import java.io.InputStream
import java.io.OutputStream

/**
 * Okio 3.x implementation of [IoProvider]. The only public type in this module.
 *
 * Install once at application startup:
 *
 * ```
 * Io.installProvider(OkioIoProvider)
 * ```
 *
 * After installation every [BufferedSource], [BufferedSink], and [Buffer] handed back by the
 * SDK is an Okio-backed adapter — `sdk-core` itself never references Okio.
 *
 * ## Thread-safety
 *
 * The provider object is stateless and safe to call from any thread. Returned adapter
 * instances follow the underlying contracts: individual [Buffer], [BufferedSource], and
 * [BufferedSink] instances are single-threaded.
 */
public object OkioIoProvider : IoProvider {
    override fun buffer(): Buffer = OkioBuffer()

    override fun source(input: InputStream): BufferedSource = OkioBufferedSource(input.source().buffer())

    override fun source(bytes: ByteArray): BufferedSource =
        // Return an OkioBuffer rather than OkioBufferedSource so callers retain the richer
        // Buffer surface (snapshot, copyTo, size, etc.). OkioBuffer implements both
        // BufferedSource and Buffer, so this is binary-compatible with the declared return
        // type while preserving every byte of the input.
        OkioBuffer(okio.Buffer().apply { write(bytes) })

    override fun sink(output: OutputStream): BufferedSink = OkioBufferedSink(output.sink().buffer())

    override fun bufferedSource(source: Source): BufferedSource =
        OkioBufferedSource(ForeignSourceAdapter(source).buffer())

    override fun bufferedSink(sink: Sink): BufferedSink = OkioBufferedSink(ForeignSinkAdapter(sink).buffer())
}
