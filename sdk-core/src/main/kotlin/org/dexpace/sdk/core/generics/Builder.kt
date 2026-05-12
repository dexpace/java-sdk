package org.dexpace.sdk.core.generics

interface Builder<out T> {
    fun build(): T
}
