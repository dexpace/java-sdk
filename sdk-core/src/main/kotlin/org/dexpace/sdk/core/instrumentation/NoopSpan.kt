package org.dexpace.sdk.core.instrumentation

object NoopSpan : Span {
    override val context: InstrumentationContext
        get() = NoopInstrumentationContext

    override val isRecording: Boolean = false

    override fun setAttribute(key: String, value: Any): Span = this

    override fun setError(errorType: String): Span = this

    override fun end(throwable: Throwable) = Unit

    override fun end() = Unit

    override fun makeCurrent(): TracingScope = TracingScope { }
}
