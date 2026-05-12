package org.dexpace.sdk.core.http.common

/**
 * Pre-constructed [MediaType] constants for the most common HTTP content types.
 *
 * Each constant is exposed as a `@JvmField` static so Java callers see a plain field
 * reference (`CommonMediaTypes.APPLICATION_JSON`) rather than a `getXxx()` accessor.
 * Reusing these instances avoids re-parsing the same media-type string on hot paths.
 */
@Suppress("unused")
object CommonMediaTypes {
    // Text Types
    @JvmField
    val TEXT_PLAIN = MediaType.of("text", "plain")

    @JvmField
    val TEXT_HTML = MediaType.of("text", "html")

    @JvmField
    val TEXT_CSS = MediaType.of("text", "css")

    @JvmField
    val TEXT_JAVASCRIPT = MediaType.of("text", "javascript")

    @JvmField
    val TEXT_CSV = MediaType.of("text", "csv")

    // Application Types
    @JvmField
    val APPLICATION_JSON = MediaType.of("application", "json")

    @JvmField
    val APPLICATION_XML = MediaType.of("application", "xml")

    @JvmField
    val APPLICATION_FORM_URLENCODED = MediaType.of("application", "x-www-form-urlencoded")

    @JvmField
    val APPLICATION_OCTET_STREAM = MediaType.of("application", "octet-stream")

    @JvmField
    val APPLICATION_PDF = MediaType.of("application", "pdf")

    @JvmField
    val APPLICATION_VND_API_JSON = MediaType.of("application", "vnd.api+json")

    @JvmField
    val APPLICATION_JSON_GRAPHQL = MediaType.of("application", "json+graphql")

    @JvmField
    val APPLICATION_HAL_JSON = MediaType.of("application", "hal+json")

    @JvmField
    val APPLICATION_PROBLEM_JSON = MediaType.of("application", "problem+json")

    @JvmField
    val APPLICATION_ZIP = MediaType.of("application", "zip")

    // Image Types
    @JvmField
    val IMAGE_JPEG = MediaType.of("image", "jpeg")

    @JvmField
    val IMAGE_PNG = MediaType.of("image", "png")

    @JvmField
    val IMAGE_GIF = MediaType.of("image", "gif")

    @JvmField
    val IMAGE_SVG_XML = MediaType.of("image", "svg+xml")

    // Audio/Video Types
    @JvmField
    val AUDIO_MPEG = MediaType.of("audio", "mpeg")

    @JvmField
    val AUDIO_WAV = MediaType.of("audio", "wav")

    @JvmField
    val VIDEO_MP4 = MediaType.of("video", "mp4")

    @JvmField
    val VIDEO_MPEG = MediaType.of("video", "mpeg")

    // Multipart Types
    @JvmField
    val MULTIPART_FORM_DATA = MediaType.of("multipart", "form-data")

    @JvmField
    val MULTIPART_BYTERANGES = MediaType.of("multipart", "byteranges")

    // Other Types
    @JvmField
    val APPLICATION_JAVASCRIPT = MediaType.of("application", "javascript")
}
