//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE

/**
 * Carrier-specific IMS behavior for the resolved home operator.
 *
 * This is intentionally an in-code policy table for now. It gives PhhIms the
 * same shape as AOSP ImsStack carrier_config defaults/overrides without adding
 * an XML/resource loader before the policy surface has stabilized.
 */

private const val TELE2_KZ_S24U_USER_AGENT = "SM-S928B-YY Samsung IMS 7.0"

private const val TELE2_KZ_FULL_EUTRAN_PANI =
    "3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=20810b8c49752501"

data class SipRegistrationRecoveryPolicy(
    val blockPcscfOnRegistrationFailure: Boolean = true,
    val pcscfBlockMs: Long = 30_000L,
    val keepFrameworkRegistrationDuringTransientSipReconnect: Boolean = true,
)

data class SipSmsPolicy(
    val fallbackSipStatusCodes: Set<Int> = setOf(403, 404, 405, 408, 480, 488, 500, 501, 503, 603),
    val fallbackCooldownMs: Long = 30L * 60L * 1000L,
    val rpResultWaitMs: Long = 15_000L,
)

data class SipInviteFailurePolicy(
    val retryAfter422: Boolean = true,
    val retryIllegalSdpStatusCodes: Set<Int> = setOf(400),
    val retryIllegalSdpWarningSubstrings: List<String> = listOf(
        "SDP is illegal",
        "illegal SDP",
    ),
    val reconnectOnAuthFailure: Boolean = true,
    val authFailureStatusCodes: Set<Int> = setOf(500),
    val authFailureMarkerSubstrings: List<String> = listOf(
        "AUTH failure",
        "not authorised",
        "not authorized",
    ),
    val authFailureReconnectDelayMs: Long = 1_000L,
    val reconnectAfterFinalFailureStatusCodes: Set<Int> = emptySet(),
    val reconnectAfterFinalFailureDelayMs: Long = 1_000L,
)

data class SipPublicNumberNormalizationPolicy(
    val kazakhstanMobileWithoutCountryCode: Boolean = false,
) {
    fun normalizeToE164(stripped: String): String? {
        if (!kazakhstanMobileWithoutCountryCode) {
            return null
        }

        return when {
            // National mobile/public form, seen from IMS as 7000000000.
            stripped.length == 10 && stripped.startsWith("7") -> "+7$stripped"

            // Trunk-prefix form used in Kazakhstan, for example 87000000000.
            stripped.length == 11 && stripped.startsWith("8") &&
                stripped.drop(1).startsWith("7") -> "+7${stripped.drop(1)}"

            // Country-code form without '+', for example 77000000000.
            stripped.length == 11 && stripped.startsWith("7") -> "+$stripped"

            else -> null
        }
    }
}

data class SipCarrierPolicy(
    val mcc: String,
    val mnc: String,
    val isControlSocketUdp: Boolean = false,
    val requireNonsessAka: Boolean = false,
    val registerExtraHeaders: SipHeadersMap = emptyMap(),
    val subscribeRegEvent: Boolean = true,
    val forceCsfbDialStrings: Set<String> = emptySet(),
    val plainTelShortCodes: Set<String> = emptySet(),
    val plainTelAllLocalShortCodes: Boolean = false,
    val outgoingPaniPolicy: OutgoingPaniPolicy = OutgoingPaniPolicy.NONE,
    val outgoingPaniOverride: String? = null,
    val outgoingInviteShape: OutgoingInviteShape = OutgoingInviteShape.DEFAULT,
    val outgoingCodecOfferPolicy: OutgoingCodecOfferPolicy =
        OutgoingCodecOfferPolicy.DEFAULT,
    val omitOutgoingInvitePrecondition: Boolean = false,
    val explicitOutgoingRtcpAttribute: Boolean = false,
    val outgoingUserAgentOverride: String? = null,
    val outgoingPreferredIdentityPolicy: OutgoingPreferredIdentityPolicy =
        OutgoingPreferredIdentityPolicy.DEFAULT,
    val outgoingFromIdentityPolicy: OutgoingFromIdentityPolicy =
        OutgoingFromIdentityPolicy.DEFAULT,
    val securityClientAlgs: List<String> = DEFAULT_SECURITY_CLIENT_ALGS,
    val securityClientEalgs: List<String> = DEFAULT_SECURITY_CLIENT_EALGS,
    val fallbackEmergencyDialStrings: Set<String> = DEFAULT_FALLBACK_EMERGENCY_DIAL_STRINGS,
    val publicNumberNormalizationPolicy: SipPublicNumberNormalizationPolicy =
        SipPublicNumberNormalizationPolicy(),
    val publicSipUriDomainOverride: String? = null,
    val publicSipUriUseKazakhstanNationalNumber: Boolean = false,
    val localTelPhoneContextUseTrunkPrefix: Boolean = false,
    val localTelPhoneContextOverride: String? = null,
    val outgoingUse3gppRealmIdentityForInvite: Boolean = false,
    val registrationRecoveryPolicy: SipRegistrationRecoveryPolicy = SipRegistrationRecoveryPolicy(),
    val smsPolicy: SipSmsPolicy = SipSmsPolicy(),
    val inviteFailurePolicy: SipInviteFailurePolicy = SipInviteFailurePolicy(),
) {
    val mccMnc: String = mcc + mnc

    fun isLocalShortCode(normalizedPhoneNumber: String): Boolean =
        normalizedPhoneNumber.length in 3..6 && normalizedPhoneNumber.all { it.isDigit() }

    fun shouldKeepShortServicePlainTel(normalizedPhoneNumber: String): Boolean =
        isLocalShortCode(normalizedPhoneNumber) &&
            (plainTelAllLocalShortCodes || normalizedPhoneNumber in plainTelShortCodes)

    fun shouldForceCsfbForDialString(normalizedPhoneNumber: String): Boolean =
        normalizedPhoneNumber in forceCsfbDialStrings

    fun isFallbackEmergencyDialString(normalizedPhoneNumber: String): Boolean =
        normalizedPhoneNumber in fallbackEmergencyDialStrings

    fun normalizePublicNumberToE164(stripped: String): String? =
        publicNumberNormalizationPolicy.normalizeToE164(stripped)

    fun phoneContextForLocalTelUri(realm: String): String {
        val candidate = realm.trim()
            .removePrefix("sip:")
            .substringBefore(";")
            .substringAfter("@")
            .trim()

        if (candidate.isNotBlank() &&
            candidate.none { it.isWhitespace() || it == '<' || it == '>' || it == '"' } &&
            !candidate.contains(":")) {
            return candidate
        }

        return "ims.mnc${normalizedMncForPhoneContext(mnc)}.mcc${mcc.trim().padStart(3, '0')}.3gppnetwork.org"
    }

    fun outgoingPaniHeaders(registrationTech: Int): SipHeadersMap {
        outgoingPaniOverride?.takeIf { it.isNotBlank() }?.let { pani ->
            return mapOf("P-Access-Network-Info" to listOf(pani))
        }

        if (outgoingPaniPolicy != OutgoingPaniPolicy.REGISTRATION_ACCESS_TECH) {
            return emptyMap()
        }

        val paniValue = when (registrationTech) {
            REGISTRATION_TECH_IWLAN -> "IEEE-802.11"
            REGISTRATION_TECH_LTE -> "3GPP-E-UTRAN-FDD"
            else -> null
        }

        return paniValue?.let { mapOf("P-Access-Network-Info" to listOf(it)) } ?: emptyMap()
    }

    fun useSingTelStockPolicy(realm: String, registerTargetRealm: String = realm): Boolean =
        outgoingInviteShape == OutgoingInviteShape.SINGTEL_COMPACT_STOCK ||
            realm.equals(SINGTEL_HOME_REALM, ignoreCase = true) ||
            realm.equals(SINGTEL_STOCK_REALM, ignoreCase = true) ||
            registerTargetRealm.equals(SINGTEL_STOCK_REALM, ignoreCase = true)

    fun registerSecurityClientAlgs(realm: String, registerTargetRealm: String = realm): List<String> =
        if (useSingTelStockPolicy(realm, registerTargetRealm)) {
            listOf("hmac-sha-1-96")
        } else {
            securityClientAlgs
        }

    fun registerSecurityClientEalgs(realm: String, registerTargetRealm: String = realm): List<String> =
        if (useSingTelStockPolicy(realm, registerTargetRealm)) {
            listOf("null")
        } else {
            securityClientEalgs
        }

    fun singtelPublicSipUri(number: String): String {
        val digits = singtelLocalNumberForPhoneContext(number)
        val e164 = if (digits.startsWith("+") || digits.startsWith("65")) {
            if (digits.startsWith("+")) digits else "+$digits"
        } else {
            "+65$digits"
        }
        return "sip:$e164@$SINGTEL_STOCK_REALM"
    }

    fun usePublicSipUriOutgoingPolicy(): Boolean =
        outgoingInviteShape == OutgoingInviteShape.PUBLIC_SIP_URI_USER_PHONE

    fun useLocalTelPhoneContextOutgoingPolicy(): Boolean =
        outgoingInviteShape == OutgoingInviteShape.LOCAL_TEL_PHONE_CONTEXT

    fun use3gppRealmIdentityForInviteOutgoingPolicy(): Boolean =
        outgoingUse3gppRealmIdentityForInvite

    fun useLocalTrunkTelPhoneContextOutgoingPolicy(): Boolean =
        outgoingInviteShape == OutgoingInviteShape.LOCAL_TRUNK_TEL_PHONE_CONTEXT

    fun useTelPreferredIdentityOutgoingPolicy(): Boolean =
        outgoingPreferredIdentityPolicy == OutgoingPreferredIdentityPolicy.TEL_URI

    fun omitPreferredIdentityOutgoingPolicy(): Boolean =
        outgoingPreferredIdentityPolicy == OutgoingPreferredIdentityPolicy.OMIT

    fun forceAmrNbOnlyOutgoingOffer(): Boolean =
        outgoingCodecOfferPolicy == OutgoingCodecOfferPolicy.AMR_NB_ONLY

    fun omitOutgoingInvitePreconditionPolicy(): Boolean =
        omitOutgoingInvitePrecondition

    fun useExplicitOutgoingRtcpAttributePolicy(): Boolean =
        explicitOutgoingRtcpAttribute

    fun useTelFromIdentityOutgoingPolicy(): Boolean =
        outgoingFromIdentityPolicy == OutgoingFromIdentityPolicy.TEL_URI

    fun publicSipUriForPhoneNumber(number: String, realm: String): String {
        val user = publicSipUriUserForPhoneNumber(number)
        val domain = publicSipUriDomainOverride ?: phoneContextForLocalTelUri(realm)
        return "sip:$user@$domain;user=phone"
    }

    fun localTelPhoneContextUriForPhoneNumber(number: String, realm: String): String {
        val localUser = publicSipUriUserForPhoneNumber(number)
        val user = if (localTelPhoneContextUseTrunkPrefix &&
            localUser.length == 10 && localUser.startsWith("7")) {
            "8$localUser"
        } else {
            localUser
        }
        val context = localTelPhoneContextOverride
            ?: publicSipUriDomainOverride
            ?: phoneContextForLocalTelUri(realm)
        return "tel:$user;phone-context=$context"
    }

    fun localTrunkTelPhoneContextUriForPhoneNumber(number: String, realm: String): String {
        val user = publicSipUriUserForPhoneNumber(number)
        val trunkUser = if (user.length == 10 && user.startsWith("7")) "8$user" else user
        val domain = publicSipUriDomainOverride ?: phoneContextForLocalTelUri(realm)
        return "tel:$trunkUser;phone-context=$domain"
    }

    private fun publicSipUriUserForPhoneNumber(number: String): String {
        val digits = number.trim()

        if (publicSipUriUseKazakhstanNationalNumber) {
            when {
                // Public E.164 +7 mobile number, for example +77000000001.
                digits.length == 12 && digits.startsWith("+7") ->
                    return digits.drop(2)

                // Kazakhstan trunk-prefix form, for example 87000000001.
                digits.length == 11 && digits.startsWith("8") &&
                    digits.drop(1).startsWith("7") -> return digits.drop(1)

                // Already in IMS-local 10-digit form, for example 7000000001.
                digits.length == 10 && digits.startsWith("7") -> return digits
            }
        }

        return if (digits.startsWith("+")) digits else "+$digits"
    }

    fun singtelSmsc(): String = SINGTEL_STOCK_SMSC

    fun smsRequestUri(realm: String, smsc: String?, smscSipIdentity: String?): String =
        if (useSingTelStockPolicy(realm)) {
            smsc?.let { "sip:${if (it.startsWith("+")) it else "+$it"}@$SINGTEL_STOCK_REALM" }
                ?: "sip:$SINGTEL_STOCK_REALM"
        } else {
            smscSipIdentity ?: "sip:$realm"
        }

    fun smsToUri(realm: String, requestUri: String, smsc: String?, smscSipIdentity: String?): String =
        if (useSingTelStockPolicy(realm)) {
            requestUri
        } else {
            smscSipIdentity ?: smsc?.let { "sip:+$it@$realm" } ?: "sip:$realm"
        }

    private fun singtelLocalNumberForPhoneContext(number: String): String {
        val digits = number.trim().trimStart('+')
        return if (digits.startsWith("65") && digits.length == 10) digits.substring(2) else digits
    }

    enum class OutgoingPaniPolicy {
        NONE,
        REGISTRATION_ACCESS_TECH,
    }

    enum class OutgoingInviteShape {
        DEFAULT,
        SINGTEL_COMPACT_STOCK,
        PUBLIC_SIP_URI_USER_PHONE,
        LOCAL_TEL_PHONE_CONTEXT,
        LOCAL_TRUNK_TEL_PHONE_CONTEXT,
    }

    enum class OutgoingCodecOfferPolicy {
        DEFAULT,
        AMR_NB_ONLY,
    }

    enum class OutgoingPreferredIdentityPolicy {
        DEFAULT,
        TEL_URI,
        OMIT,
    }

    enum class OutgoingFromIdentityPolicy {
        DEFAULT,
        TEL_URI,
    }

    companion object {
        val DEFAULT_SECURITY_CLIENT_ALGS = listOf("hmac-sha-1-96", "hmac-md5-96")
        val DEFAULT_SECURITY_CLIENT_EALGS = listOf("null", "aes-cbc")

        private const val SINGTEL_HOME_REALM = "ims.mnc001.mcc525.3gppnetwork.org"
        private const val SINGTEL_STOCK_REALM = "ims.singtel.com"
        private const val SINGTEL_STOCK_SMSC = "+6596197777"

        val DEFAULT_FALLBACK_EMERGENCY_DIAL_STRINGS = setOf(
            "000", // AU and others
            "110", // DE police and others
            "112", // EU/common emergency
            "118",
            "119",
            "911", // NANP/common emergency
            "999", // UK/common emergency
        )

        fun normalizedMncForPhoneContext(mnc: String): String =
            mnc.trim().trimStart('0').ifBlank { "0" }.padStart(3, '0')

        fun defaultFor(mcc: String, mnc: String): SipCarrierPolicy =
            SipCarrierPolicy(mcc = mcc, mnc = mnc)

        fun forHomeOperator(mcc: String, mnc: String): SipCarrierPolicy {
            val mccMnc = mcc + mnc
            return when (mccMnc) {
                "219010" -> defaultFor(mcc, mnc).copy(
                    // A1 HR needs stock access-network headers on REGISTER and
                    // does not require the optional reg-event SUBSCRIBE to mark
                    // IMS voice ready.
                    registerExtraHeaders = mapOf(
                        "P-Access-Network-Info" to listOf("3GPP-E-UTRAN-FDD"),
                        "P-Visited-Network-ID" to listOf("\"ims.mnc010.mcc219.3gppnetwork.org\""),
                    ),
                    subscribeRegEvent = false,
                )

                "232005" -> defaultFor(mcc, mnc).copy(
                    // 3 AT service code 333 is not a normal IMS MMTel target.
                    forceCsfbDialStrings = setOf("333"),
                )

                "286002" -> defaultFor(mcc, mnc).copy(
                    // Vodafone TR confirmed short service codes as plain TEL
                    // and needs access-tech PANI on outgoing INVITE.
                    plainTelShortCodes = setOf("542"),
                    outgoingPaniPolicy = OutgoingPaniPolicy.REGISTRATION_ACCESS_TECH,
                )

                "401077" -> defaultFor(mcc, mnc).copy(
                    // KZ IMS exposes +7 mobile/public numbers without the
                    // country code on HD calls and rejects outgoing TEL URIs
                    // built from that local form. It also rejects originating
                    // MMTEL INVITEs that keep the public target as a TEL URI,
                    // so send public E.164 call targets as SIP user=phone URIs.
                    publicNumberNormalizationPolicy =
                        SipPublicNumberNormalizationPolicy(
                            kazakhstanMobileWithoutCountryCode = true,
                        ),
                    outgoingPaniPolicy = OutgoingPaniPolicy.REGISTRATION_ACCESS_TECH,
                    // Tele2 KZ rejects local-TEL INVITEs after number validation
                    // with an OCS CCA failure when only access tech is sent.
                    // Try the same full E-UTRAN PANI shape used on reg-event.
                    outgoingPaniOverride = TELE2_KZ_FULL_EUTRAN_PANI,
                    // Tele2 KZ reaches OCS with the local TEL target but still
                    // fails charging. Try presenting own IMPU as a TEL URI
                    // in P-Preferred-Identity instead of SIP URI.
                    outgoingPreferredIdentityPolicy = OutgoingPreferredIdentityPolicy.DEFAULT,
                    // TEL PPI still reaches OCS but fails charging. Try a
                    // matching TEL From identity while preserving the tag.
                    outgoingFromIdentityPolicy =
                        OutgoingFromIdentityPolicy.DEFAULT,
                    // URI/identity/PANI tests still reach OCS but fail.
                    // Try a narrowband-only outgoing SDP offer.
                    outgoingCodecOfferPolicy =
                        OutgoingCodecOfferPolicy.DEFAULT,
                    // Previous URI, identity, PANI and codec variants still fail at OCS.
                    // Test a plain SDP offer without RFC3312 QoS/precondition.
                    omitOutgoingInvitePrecondition = false,
                    outgoingInviteShape = OutgoingInviteShape.LOCAL_TEL_PHONE_CONTEXT,
                    // Stock-ish media test after URI/identity variants still hit OCS.
                    // Keep local TEL routing, restore normal SDP, and advertise RTCP.
                    explicitOutgoingRtcpAttribute = true,
                    // Test whether Tele2 KZ buckets outgoing VoLTE by
                    // the Samsung IMS profile/model in User-Agent.
                    outgoingUserAgentOverride =
                        TELE2_KZ_S24U_USER_AGENT,
                    // Test originating identity using the 3GPP associated URI.
                    // Expected From/PPI: sip:+own@ims.mnc077.mcc401.3gppnetwork.org;user=phone
                    outgoingUse3gppRealmIdentityForInvite = false,
                    // Test global-prefix TEL phone-context.
                    // Expected target: tel:7000000001;phone-context=+7
                    localTelPhoneContextOverride = "+7",
                    // Test national trunk-prefix TEL user for the local phone-context target.
                    // Expected target: tel:7000000001;phone-context=ims.mnc077.mcc401.3gppnetwork.org
                    localTelPhoneContextUseTrunkPrefix = false,
                    // REGISTER exposes the public IMPU in the Altel domain.
                    // Use the same public domain for called-party SIP URIs;
                    // Tele2 KZ rejects targets under the generic 3GPP realm
                    // with "The called num is invalid."
                    publicSipUriDomainOverride = null,
                    // Tele2 KZ still rejects +7 called-party SIP users as
                    // invalid. Incoming HD caller IDs arrive in the IMS-local
                    // 10-digit form, so try the same user part for outbound
                    // Request-URI/To while keeping framework numbers E.164.
                    publicSipUriUseKazakhstanNationalNumber = true,
                )

                "450006" -> defaultFor(mcc, mnc).copy(
                    // LG U+ can only do UDP and requires non-session AKA.
                    isControlSocketUdp = true,
                    requireNonsessAka = true,
                )

                "525001" -> defaultFor(mcc, mnc).copy(
                    // SingTel needs a stock-like compact outgoing INVITE/SMS
                    // target and restricted security offer.
                    outgoingInviteShape = OutgoingInviteShape.SINGTEL_COMPACT_STOCK,
                    securityClientAlgs = listOf("hmac-sha-1-96"),
                    securityClientEalgs = listOf("null"),
                )

                "208010" -> defaultFor(mcc, mnc).copy(
                    // 20810 can do TCP and UDP; force UDP for testing.
                    isControlSocketUdp = true,
                )

                else -> defaultFor(mcc, mnc)
            }
        }
    }
}

/**
 * Resolved home-operator carrier settings.
 *
 * Keep compatibility forwarding methods here during the policy migration so
 * callers can be moved over incrementally without breaking partial trees.
 */
data class SipCarrierSettings(
    val mcc: String,
    val mnc: String,
    val policy: SipCarrierPolicy,
) {
    val mccMnc: String get() = policy.mccMnc
    val isControlSocketUdp: Boolean get() = policy.isControlSocketUdp
    val requireNonsessAka: Boolean get() = policy.requireNonsessAka
    val registerExtraHeaders: SipHeadersMap get() = policy.registerExtraHeaders
    val subscribeRegEvent: Boolean get() = policy.subscribeRegEvent
    val registrationRecoveryPolicy: SipRegistrationRecoveryPolicy get() = policy.registrationRecoveryPolicy
    val smsPolicy: SipSmsPolicy get() = policy.smsPolicy
    val inviteFailurePolicy: SipInviteFailurePolicy get() = policy.inviteFailurePolicy

    // Legacy names kept while callers are converted.
    val registerNetworkHeaders: SipHeadersMap get() = policy.registerExtraHeaders
    val skipRegEventSubscribe: Boolean get() = !policy.subscribeRegEvent

    fun isLocalShortCode(normalizedPhoneNumber: String): Boolean =
        policy.isLocalShortCode(normalizedPhoneNumber)

    fun shouldKeepShortServicePlainTel(normalizedPhoneNumber: String): Boolean =
        policy.shouldKeepShortServicePlainTel(normalizedPhoneNumber)

    fun shouldForceCsfbForDialString(normalizedPhoneNumber: String): Boolean =
        policy.shouldForceCsfbForDialString(normalizedPhoneNumber)

    fun shouldForceCsfbForDialCode(normalizedPhoneNumber: String): Boolean =
        shouldForceCsfbForDialString(normalizedPhoneNumber)

    fun isFallbackEmergencyDialString(normalizedPhoneNumber: String): Boolean =
        policy.isFallbackEmergencyDialString(normalizedPhoneNumber)

    fun normalizePublicNumberToE164(stripped: String): String? =
        policy.normalizePublicNumberToE164(stripped)

    fun phoneContextForLocalTelUri(realm: String): String =
        policy.phoneContextForLocalTelUri(realm)

    fun outgoingPaniHeaders(registrationTech: Int): SipHeadersMap =
        policy.outgoingPaniHeaders(registrationTech)

    fun useSingTelStockPolicy(realm: String, registerTargetRealm: String = realm): Boolean =
        policy.useSingTelStockPolicy(realm, registerTargetRealm)

    fun registerSecurityClientAlgs(realm: String, registerTargetRealm: String = realm): List<String> =
        policy.registerSecurityClientAlgs(realm, registerTargetRealm)

    fun registerSecurityClientEalgs(realm: String, registerTargetRealm: String = realm): List<String> =
        policy.registerSecurityClientEalgs(realm, registerTargetRealm)

    fun singtelPublicSipUri(number: String): String =
        policy.singtelPublicSipUri(number)

    fun usePublicSipUriOutgoingPolicy(): Boolean =
        policy.usePublicSipUriOutgoingPolicy()

    fun useLocalTelPhoneContextOutgoingPolicy(): Boolean =
        policy.useLocalTelPhoneContextOutgoingPolicy()

    fun use3gppRealmIdentityForInviteOutgoingPolicy(): Boolean =
        policy.use3gppRealmIdentityForInviteOutgoingPolicy()

    fun useLocalTrunkTelPhoneContextOutgoingPolicy(): Boolean =
        policy.useLocalTrunkTelPhoneContextOutgoingPolicy()

    fun useTelPreferredIdentityOutgoingPolicy(): Boolean =
        policy.useTelPreferredIdentityOutgoingPolicy()

    fun omitPreferredIdentityOutgoingPolicy(): Boolean =
        policy.omitPreferredIdentityOutgoingPolicy()

    fun forceAmrNbOnlyOutgoingOffer(): Boolean =
        policy.forceAmrNbOnlyOutgoingOffer()

    fun omitOutgoingInvitePreconditionPolicy(): Boolean =
        policy.omitOutgoingInvitePreconditionPolicy()

    fun useExplicitOutgoingRtcpAttributePolicy(): Boolean =
        policy.useExplicitOutgoingRtcpAttributePolicy()

    fun outgoingUserAgentOverridePolicy(): String? =
        policy.outgoingUserAgentOverride

    fun useTelFromIdentityOutgoingPolicy(): Boolean =
        policy.useTelFromIdentityOutgoingPolicy()

    fun publicSipUriForPhoneNumber(number: String, realm: String): String =
        policy.publicSipUriForPhoneNumber(number, realm)

    fun localTelPhoneContextUriForPhoneNumber(number: String, realm: String): String =
        policy.localTelPhoneContextUriForPhoneNumber(number, realm)

    fun localTrunkTelPhoneContextUriForPhoneNumber(number: String, realm: String): String =
        policy.localTrunkTelPhoneContextUriForPhoneNumber(number, realm)

    fun singtelSmsc(): String =
        policy.singtelSmsc()

    fun outgoingSmscForImsSms(realm: String, discoveredSmsc: String?): String? =
        if (useSingTelStockPolicy(realm)) singtelSmsc() else discoveredSmsc

    fun smsRequestUri(realm: String, smsc: String?, smscSipIdentity: String?): String =
        policy.smsRequestUri(realm, smsc, smscSipIdentity)

    fun outgoingSmsRequestUri(realm: String, smsc: String?, smscSipIdentity: String?): String =
        smsRequestUri(realm, smsc, smscSipIdentity)

    fun smsToUri(realm: String, requestUri: String, smsc: String?, smscSipIdentity: String?): String =
        policy.smsToUri(realm, requestUri, smsc, smscSipIdentity)

    fun outgoingSmsToUri(realm: String, requestUri: String, smsc: String?, smscSipIdentity: String?): String =
        smsToUri(realm, requestUri, smsc, smscSipIdentity)

    companion object {
        fun isLocalShortCode(normalizedPhoneNumber: String): Boolean =
            normalizedPhoneNumber.length in 3..6 && normalizedPhoneNumber.all { it.isDigit() }

        fun normalizedMncForPhoneContext(mnc: String): String =
            SipCarrierPolicy.normalizedMncForPhoneContext(mnc)

        fun fromSimOperator(simOperator: String): SipCarrierSettings {
            val mcc = simOperator.substring(0 until 3)
            val mnc = simOperator.substring(3).let { if (it.length == 2) "0$it" else it }
            val policy = SipCarrierPolicy.forHomeOperator(mcc, mnc)

            return SipCarrierSettings(
                mcc = mcc,
                mnc = mnc,
                policy = policy,
            )
        }
    }
}
