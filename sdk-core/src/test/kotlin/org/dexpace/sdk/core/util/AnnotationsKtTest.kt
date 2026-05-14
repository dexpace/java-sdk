package org.dexpace.sdk.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the inline reified extensions in `Annotations.kt` — `Any.getAnnotation<T>()` and
 * `Any.hasAnnotation<T>()`. Both walk only the directly-declared annotations on the runtime
 * class, so the fixtures use a single annotation directly on a class (the "present" case)
 * and a bare class without it (the "absent" case).
 */
class AnnotationsKtTest {
    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.CLASS)
    annotation class SampleMarker(val tag: String = "default")

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.CLASS)
    annotation class OtherMarker

    @SampleMarker(tag = "hello")
    private class Annotated

    private class NotAnnotated

    @Test
    fun `getAnnotation returns the annotation instance when present`() {
        val obj = Annotated()
        val a: SampleMarker? = obj.getAnnotation<SampleMarker>()
        assertNotNull(a)
        assertEquals("hello", a.tag)
    }

    @Test
    fun `getAnnotation returns null when the annotation is absent`() {
        val obj = NotAnnotated()
        val a: SampleMarker? = obj.getAnnotation<SampleMarker>()
        assertNull(a)
    }

    @Test
    fun `getAnnotation returns null when a different annotation is present but not the requested one`() {
        val obj = Annotated()
        val other: OtherMarker? = obj.getAnnotation<OtherMarker>()
        assertNull(other)
    }

    @Test
    fun `hasAnnotation is true when the annotation is present`() {
        assertTrue(Annotated().hasAnnotation<SampleMarker>())
    }

    @Test
    fun `hasAnnotation is false when the annotation is absent`() {
        assertFalse(NotAnnotated().hasAnnotation<SampleMarker>())
        assertFalse(Annotated().hasAnnotation<OtherMarker>())
    }
}
