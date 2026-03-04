package org.dexpace.sdk.core

class Main {
    val canonicalName: String = this.javaClass.canonicalName
    val name: String = this.javaClass.name
    val simpleName: String = this.javaClass.simpleName
    val packageName: String = this.javaClass.`package`.name
}

fun main() {
    val main = Main()
    println(main.canonicalName)
}