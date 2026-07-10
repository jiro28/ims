// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.Rlog

/**
 * RFC 4028 session-timer role negotiation.
 *
 * Keep timer header policy separate from SipHandler. PhhIms currently accepts
 * peer refreshes but does not originate periodic refreshes, so prefer the peer
 * as refresher whenever the initial UAC left the role open.
 */
internal object SipSessionTimerNegotiation {
    private const val MIN_INTERVAL_SECONDS = 90

    fun outgoingRequestValue(intervalSeconds: Int): String =
        "${intervalSeconds.coerceAtLeast(MIN_INTERVAL_SECONDS)};refresher=uas"

    fun responseHeadersForIncomingRequest(
        requestHeaders: SipHeadersMap,
        logTag: String,
    ): SipHeadersMap {
        val rawSessionExpires = firstHeader(requestHeaders, "session-expires")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyMap()
        val interval = rawSessionExpires
            .substringBefore(';')
            .trim()
            .toIntOrNull()
            ?.takeIf { it >= MIN_INTERVAL_SECONDS }
            ?: run {
                Rlog.w(logTag, "Ignoring invalid Session-Expires value: $rawSessionExpires")
                return emptyMap()
            }
        val requestedRefresher = parameter(rawSessionExpires, "refresher")
        val peerSupportsTimer = headerContainsToken(requestHeaders, "supported", "timer")
        val responseRefresher = when {
            requestedRefresher == "uac" -> "uac"
            requestedRefresher == "uas" -> "uas"
            peerSupportsTimer -> "uac"
            else -> "uas"
        }

        if (responseRefresher == "uas") {
            Rlog.w(
                logTag,
                "Peer selected local session refresher; periodic local refresh is not yet supported",
            )
        }

        val requireTimer = responseRefresher == "uac" || peerSupportsTimer
        val headers = mutableMapOf<String, List<String>>(
            "session-expires" to listOf("$interval;refresher=$responseRefresher"),
        )
        if (requireTimer) {
            headers["require"] = listOf("timer")
        }
        return headers
    }

    private fun parameter(value: String, name: String): String? =
        value
            .split(';')
            .asSequence()
            .drop(1)
            .map { it.trim() }
            .firstOrNull { it.substringBefore('=').trim().equals(name, ignoreCase = true) }
            ?.substringAfter('=', "")
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }

    private fun headerContainsToken(
        headers: SipHeadersMap,
        name: String,
        token: String,
    ): Boolean = firstHeaders(headers, name)
        .flatMap { it.split(',') }
        .any { it.trim().equals(token, ignoreCase = true) }

    private fun firstHeader(headers: SipHeadersMap, name: String): String? =
        firstHeaders(headers, name).firstOrNull()

    private fun firstHeaders(headers: SipHeadersMap, name: String): List<String> =
        headers[name]
            ?: headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            ?: emptyList()
}
