package org.dexpace.sdk.core.util

inline fun <reified T> Any.getAnnotation() : T? =
    this::class.annotations.find { it is T } as? T

inline fun <reified T> Any.hasAnnotation() : Boolean =
    this::class.annotations.find { it is T } != null
