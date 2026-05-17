package org.dexpace.sdk.core.util

/**
 * Static descriptor of the running SDK build and host JVM environment, used to construct the
 * default identity tokens emitted by
 * [org.dexpace.sdk.core.pipeline.step.ClientIdentityStep] (e.g. `dexpace-sdk/<ver>`,
 * `jvm/<javaver>`).
 *
 * ## Resolution
 *
 * - [sdkVersion] is read from the JAR manifest's `Implementation-Version` attribute
 *   (i.e. [Package.getImplementationVersion]). When the SDK is run from a JAR with a
 *   properly populated manifest — which is the case for every published artifact, since
 *   the root build wires the version into the Jar task — this returns the Gradle project
 *   version. When the SDK is loaded from classes-on-disk (e.g. during local unit tests
 *   where there is no enclosing JAR), [Package.getImplementationVersion] returns `null`
 *   and this property falls back to the literal string `"unknown"`. The fallback is
 *   intentional: identity tokens must always render a non-blank value so that
 *   downstream `joinToString(" ")` cannot produce a malformed `User-Agent`.
 * - [javaVersion], [javaVendor], [osName] are read from `System.getProperty(...)`. The
 *   same `"unknown"` fallback applies if a property is unset (uncommon but possible in
 *   stripped-down embedded JVMs).
 *
 * ## Thread-safety
 *
 * All members are immutable, evaluated once at class-init time. Safe to share.
 */
public object SdkInfo {
    /**
     * The SDK's `Implementation-Version` as embedded in the JAR manifest, or `"unknown"`
     * when running from class files without a manifest (e.g. local unit tests).
     */
    public val sdkVersion: String =
        SdkInfo::class.java.`package`?.implementationVersion ?: UNKNOWN

    /**
     * Value of the `java.version` system property, or `"unknown"` if the property is not
     * set.
     */
    public val javaVersion: String = System.getProperty("java.version") ?: UNKNOWN

    /**
     * Value of the `java.vendor` system property, or `"unknown"` if the property is not
     * set.
     */
    public val javaVendor: String = System.getProperty("java.vendor") ?: UNKNOWN

    /**
     * Value of the `os.name` system property, or `"unknown"` if the property is not set.
     */
    public val osName: String = System.getProperty("os.name") ?: UNKNOWN

    /**
     * Returns the default ordered token list used by
     * [org.dexpace.sdk.core.pipeline.step.ClientIdentityStep]'s builder when no tokens are
     * supplied explicitly: `["dexpace-sdk/<sdkVersion>", "jvm/<javaVersion>"]`.
     *
     * Transport adapters and applications can append additional tokens
     * (e.g. `okhttp/5.0.0`) via the step's builder.
     */
    public fun defaultTokens(): List<String> =
        listOf(
            "dexpace-sdk/$sdkVersion",
            "jvm/$javaVersion",
        )

    private const val UNKNOWN: String = "unknown"
}
