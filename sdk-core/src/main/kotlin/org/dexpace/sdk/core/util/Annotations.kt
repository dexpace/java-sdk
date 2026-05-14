package org.dexpace.sdk.core.util

/**
 * Reflectively finds the first annotation of type [T] on `this` instance's runtime class and returns
 * it cast to [T], or `null` if no such annotation is present.
 *
 * Inlined and `reified` so callers can write `obj.getAnnotation<MyAnnotation>()` without passing a
 * `KClass`. Note that this only inspects annotations declared directly on the class — it does not
 * walk supertypes, interfaces, or meta-annotations.
 */
public inline fun <reified T> Any.getAnnotation(): T? =
    this::class.annotations.firstOrNull { it is T } as? T

/**
 * Reflectively reports whether `this` instance's runtime class declares an annotation of type [T].
 *
 * Convenience over [getAnnotation] when only the presence — not the annotation's properties —
 * matters. Same limitations: direct annotations only, no supertype or meta-annotation traversal.
 */
public inline fun <reified T> Any.hasAnnotation(): Boolean =
    this::class.annotations.any { it is T }
