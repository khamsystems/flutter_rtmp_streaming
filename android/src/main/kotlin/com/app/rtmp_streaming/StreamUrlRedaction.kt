package com.app.rtmp_streaming

/**
 * Renders a publish URL safe to write to logcat.
 *
 * The stream key is the last path segment of an RTMP/RTMPS publish URL, and with
 * providers like Cloudflare Stream that segment *is* the whole credential --
 * anyone reading it can publish to the input. logcat is readable by adb and by
 * crash/telemetry collectors, so the raw URL must never be logged.
 *
 * Everything up to the last '/' is kept: scheme, host, port and app path are the
 * parts that actually matter when debugging a connect failure. The key itself is
 * masked down to its last four characters, which is enough to tell which key is
 * in use without being enough to publish with.
 *
 * Any query string is dropped wholesale rather than parsed -- some providers pass
 * auth tokens there, and dropping is the safe default for a shape we have not
 * enumerated.
 */
internal fun redactStreamUrl(url: String?): String {
    if (url.isNullOrEmpty()) return "(none)"

    val withoutQuery = url.substringBefore('?')
    val hadQuery = withoutQuery.length != url.length
    val cut = withoutQuery.lastIndexOf('/')

    // No path separator at all: we cannot tell URL from key, so keep none of it.
    if (cut < 0) return "<redacted>"

    val head = withoutQuery.substring(0, cut)
    val key = withoutQuery.substring(cut + 1)
    val masked = if (key.length <= 4) "****" else "****${key.takeLast(4)}"

    return head + "/" + masked + if (hadQuery) " (+query redacted)" else ""
}
