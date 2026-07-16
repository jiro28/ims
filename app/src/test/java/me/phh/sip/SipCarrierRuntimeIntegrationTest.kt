// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SipCarrierRuntimeIntegrationTest {
    @Test
    fun registrationContactKeepsSmsWhileVoiceIsDisabled() {
        val contact = SipContactHeaders.registrationContact(
            userPart = "user",
            localEndpoint = "192.0.2.1:5060",
            transport = "udp",
            sipInstance = "<urn:gsma:imei:12345678-123456-0>",
            voiceEnabled = false,
        )

        assertTrue(contact.contains("+g.3gpp.smsip"))
        assertFalse(contact.contains("3gpp-service.ims.icsi.mmtel"))
        assertFalse(contact.endsWith(";audio"))
    }

    @Test
    fun registrationContactAdvertisesVoiceWhenEnabled() {
        val contact = SipContactHeaders.registrationContact(
            userPart = "user",
            localEndpoint = "192.0.2.1:5060",
            transport = "udp",
            sipInstance = "<urn:gsma:imei:12345678-123456-0>",
            voiceEnabled = true,
        )

        assertTrue(contact.contains("+g.3gpp.smsip"))
        assertTrue(contact.contains("3gpp-service.ims.icsi.mmtel"))
        assertTrue(contact.endsWith(";audio"))
    }

    @Test
    fun initialInviteCsfbUsesInitiatingFailureRouting() {
        assertEquals(
            mapOf(
                "callStartFailed" to "true",
                "outgoingCall" to "true",
                "csRetry" to "true",
            ),
            SipOutgoingInviteProgressResponses.outgoingFailureRoutingExtras(
                initialInviteFailed = true,
                csRetry = true,
            ),
        )
    }

    @Test
    fun establishedDialogFailureDoesNotRequestInitiatingFailure() {
        assertTrue(
            SipOutgoingInviteProgressResponses.outgoingFailureRoutingExtras(
                initialInviteFailed = false,
                csRetry = false,
            ).isEmpty(),
        )
    }
}
