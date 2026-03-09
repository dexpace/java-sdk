package org.dexpace.sdk.core.generics

fun interface BuilderTrait<out T> {
    fun build(): T
}