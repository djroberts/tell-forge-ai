package dev.tellforgeai.ui

import java.util.Base64

/**
 * "⧉ copy" affordance for code/output blocks in the chat transcript.
 *
 * Swing's HTML renderer can't overlay floating buttons, so each block gets a
 * plain right-aligned link ("⧉ copy") whose href carries the block's raw text
 * base64url-encoded — that alphabet is HTML-safe, so no escaping/quoting
 * issues, and no per-block registry to keep in sync across transcript rebuilds.
 * [ChatPanel]'s hyperlink listener recognizes [SCHEME] and writes the payload
 * to the system clipboard.
 */
internal object CopyLink {
    /** href prefix identifying a copy-affordance link in the transcript. */
    const val SCHEME = "tellforgeai:copy:"

    /** HTML fragment rendering the copy link for [text] (the payload to copy). */
    fun html(text: String): String {
        val b64 = encode(text)
        return "<table width='100%' cellpadding=0 cellspacing=0><tr>" +
            "<td align='right'><span style='font-size:0.85em'>" +
            "<a href='$SCHEME$b64'>⧉ copy</a></span></td></tr></table>"
    }

    /** Extracts the copyable text from a full href; null for malformed/foreign links. */
    fun textFromHref(href: String): String? {
        if (!href.startsWith(SCHEME)) return null
        // Empty payload is reserved: producers never emit copy links for blank
        // blocks, so treat it as malformed rather than copying "".
        val payload = href.removePrefix(SCHEME)
        if (payload.isEmpty()) return null
        return runCatching {
            String(Base64.getUrlDecoder().decode(payload))
        }.getOrNull()
    }

    private fun encode(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
}
