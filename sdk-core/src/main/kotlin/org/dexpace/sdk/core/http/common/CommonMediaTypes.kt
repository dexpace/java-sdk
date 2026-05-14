package org.dexpace.sdk.core.http.common

/**
 * Pre-constructed [MediaType] constants for the most common HTTP content types.
 *
 * Each constant is exposed as a `@JvmField` static so Java callers see a plain field
 * reference (`CommonMediaTypes.APPLICATION_JSON`) rather than a `getXxx()` accessor.
 * Reusing these instances avoids re-parsing the same media-type string on hot paths.
 */
@Suppress("unused")
public object CommonMediaTypes {
    // Text Types
    @JvmField
    public val TEXT_PLAIN: MediaType = MediaType.of("text", "plain")

    @JvmField
    public val TEXT_HTML: MediaType = MediaType.of("text", "html")

    @JvmField
    public val TEXT_CSS: MediaType = MediaType.of("text", "css")

    @JvmField
    public val TEXT_JAVASCRIPT: MediaType = MediaType.of("text", "javascript")

    @JvmField
    public val TEXT_CSV: MediaType = MediaType.of("text", "csv")

    // Application Types
    @JvmField
    public val APPLICATION_JSON: MediaType = MediaType.of("application", "json")

    @JvmField
    public val APPLICATION_XML: MediaType = MediaType.of("application", "xml")

    @JvmField
    public val APPLICATION_FORM_URLENCODED: MediaType = MediaType.of("application", "x-www-form-urlencoded")

    @JvmField
    public val APPLICATION_OCTET_STREAM: MediaType = MediaType.of("application", "octet-stream")

    @JvmField
    public val APPLICATION_PDF: MediaType = MediaType.of("application", "pdf")

    @JvmField
    public val APPLICATION_VND_API_JSON: MediaType = MediaType.of("application", "vnd.api+json")

    @JvmField
    public val APPLICATION_JSON_GRAPHQL: MediaType = MediaType.of("application", "json+graphql")

    @JvmField
    public val APPLICATION_HAL_JSON: MediaType = MediaType.of("application", "hal+json")

    @JvmField
    public val APPLICATION_PROBLEM_JSON: MediaType = MediaType.of("application", "problem+json")

    @JvmField
    public val APPLICATION_ZIP: MediaType = MediaType.of("application", "zip")

    // Image Types
    @JvmField
    public val IMAGE_JPEG: MediaType = MediaType.of("image", "jpeg")

    @JvmField
    public val IMAGE_PNG: MediaType = MediaType.of("image", "png")

    @JvmField
    public val IMAGE_GIF: MediaType = MediaType.of("image", "gif")

    @JvmField
    public val IMAGE_SVG_XML: MediaType = MediaType.of("image", "svg+xml")

    // Audio/Video Types
    @JvmField
    public val AUDIO_MPEG: MediaType = MediaType.of("audio", "mpeg")

    @JvmField
    public val AUDIO_WAV: MediaType = MediaType.of("audio", "wav")

    @JvmField
    public val VIDEO_MP4: MediaType = MediaType.of("video", "mp4")

    @JvmField
    public val VIDEO_MPEG: MediaType = MediaType.of("video", "mpeg")

    // Multipart Types
    @JvmField
    public val MULTIPART_FORM_DATA: MediaType = MediaType.of("multipart", "form-data")

    @JvmField
    public val MULTIPART_BYTERANGES: MediaType = MediaType.of("multipart", "byteranges")

    // Other Types
    @JvmField
    public val APPLICATION_JAVASCRIPT: MediaType = MediaType.of("application", "javascript")
}
