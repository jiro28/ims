//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE

/**
 * Central home-operator IMS policy.
 *
 * Keep carrier-specific SIP shape decisions here instead of scattering MCC/MNC
 * checks through SipHandler, INVITE, REGISTER, and SMS code paths. This is a
 * small in-code equivalent of AOSP ImsStack carrier_config defaults/overrides:
 * generic behavior stays in the callers, while exceptional carrier knobs live
 * in one table keyed by the resolved home operator numeric.
 */
data class SipCarrierSettings(
    val mcc: String,
    val mnc: String,
    val isControlSocketUdp: Boolean,
    val requireNonsessAka: Boolean,
    val registerNetworkHeaders: SipHeadersMap = emptyMap(),
    val skipRegEventSubscribe: Boolean = false,
    val forceCsfbDialCodes: Set<String> = emptySet(),
    val keepShortServiceCodesAsPlainTel: Boolean = false,
    val outgoingAccessTechPani: Boolean = false,
    val singtelStockPolicy: Boolean = false,
) {
    val mccMnc: String = mcc + mnc

    fun registerSecurityClientAlgs(realm: String, registerTargetRealm: String = realm): List<String> =
        if (useSingTelStockPolicy(realm, registerTargetRealm)) {
            listOf("hmac-sha-1-96")
        } else {
            DEFAULT_SECURITY_CLIENT_ALGS
        }

    fun registerSecurityClientEalgs(realm: String, registerTargetRealm: String = realm): List<String> =
        if (useSingTelStockPolicy(realm, registerTargetRealm)) {
            listOf("null")
        } else {
            DEFAULT_SECURITY_CLIENT_EALGS
        }

    fun shouldForceCsfbForDialCode(normalizedPhoneNumber: String): Boolean =
        normalizedPhoneNumber in forceCsfbDialCodes

    fun shouldKeepShortServicePlainTel(normalizedPhoneNumber: String): Boolean =
        keepShortServiceCodesAsPlainTel && isLocalShortCode(normalizedPhoneNumber)

    fun outgoingPaniHeaders(registrationTech: Int): SipHeadersMap {
        if (!outgoingAccessTechPani) return emptyMap()

        val paniValue = when (registrationTech) {
            REGISTRATION_TECH_IWLAN -> "IEEE-802.11"
            REGISTRATION_TECH_LTE -> "3GPP-E-UTRAN-FDD"
            else -> null
        }

        return paniValue?.let { mapOf("P-Access-Network-Info" to listOf(it)) } ?: emptyMap()
    }

    fun useSingTelStockPolicy(realm: String, registerTargetRealm: String = realm): Boolean =
        singtelStockPolicy ||
            realm.equals(SINGTEL_HOME_REALM, ignoreCase = true) ||
            realm.equals(SINGTEL_STOCK_REALM, ignoreCase = true) ||
            registerTargetRealm.equals(SINGTEL_STOCK_REALM, ignoreCase = true)

    fun singtelPublicSipUri(number: String): String {
        val digits = singtelLocalNumber(number)
        val e164 = if (digits.startsWith("+") || digits.startsWith("65")) {
            if (digits.startsWith("+")) digits else "+$digits"
        } else {
            "+65$digits"
        }
        return "sip:$e164@$SINGTEL_STOCK_REALM"
    }

    fun outgoingSmscForImsSms(realm: String, discoveredSmsc: String?): String? =
        if (useSingTelStockPolicy(realm)) SINGTEL_STOCK_SMSC else discoveredSmsc

    fun outgoingSmsRequestUri(realm: String, smsc: String?, smscSipIdentity: String?): String =
        if (useSingTelStockPolicy(realm)) {
            smsc?.let { "sip:${if (it.startsWith("+")) it else "+$it"}@$SINGTEL_STOCK_REALM" }
                ?: "sip:$SINGTEL_STOCK_REALM"
        } else {
            smscSipIdentity ?: "sip:$realm"
        }

    fun outgoingSmsToUri(realm: String, requestUri: String, smsc: String?, smscSipIdentity: String?): String =
        if (useSingTelStockPolicy(realm)) {
            requestUri
        } else {
            smscSipIdentity ?: smsc?.let { "sip:+$it@$realm" } ?: "sip:$realm"
        }

    private fun singtelLocalNumber(number: String): String {
        val digits = number.trim().trimStart('+')
        return if (digits.startsWith("65") && digits.length == 10) digits.substring(2) else digits
    }

    companion object {
        private val DEFAULT_SECURITY_CLIENT_ALGS = listOf("hmac-sha-1-96", "hmac-md5-96")
        private val DEFAULT_SECURITY_CLIENT_EALGS = listOf("null", "aes-cbc")

        private const val SINGTEL_HOME_REALM = "ims.mnc001.mcc525.3gppnetwork.org"
        private const val SINGTEL_STOCK_REALM = "ims.singtel.com"
        private const val SINGTEL_STOCK_SMSC = "+6596197777"

        fun isLocalShortCode(normalizedPhoneNumber: String): Boolean =
            normalizedPhoneNumber.length in 3..6 && normalizedPhoneNumber.all { it.isDigit() }

        fun normalizedMncForPhoneContext(mnc: String): String =
            mnc.trim().trimStart('0').ifBlank { "0" }.padStart(3, '0')

        fun fromSimOperator(simOperator: String): SipCarrierSettings {
            val mcc = simOperator.substring(0 until 3)
            val mnc = simOperator.substring(3).let { if (it.length == 2) "0$it" else it }
            val mccMnc = mcc + mnc

            return when (mccMnc) {
                "219010" -> defaultFor(mcc, mnc).copy(
                    // A1 HR challenges with realm=vip.hr but expects REGISTER
                    // to stay on the home IMS domain and needs the stock access
                    // network headers on REGISTER.
                    registerNetworkHeaders = mapOf(
                        "P-Access-Network-Info" to listOf("3GPP-E-UTRAN-FDD"),
                        "P-Visited-Network-ID" to listOf("\"ims.mnc010.mcc219.3gppnetwork.org\""),
                    ),
                    skipRegEventSubscribe = true,
                )

                "232005" -> defaultFor(mcc, mnc).copy(
                    // 3 AT strips its service code before this path; send it to
                    // CS instead of building a broken IMS INVITE.
                    forceCsfbDialCodes = setOf("333"),
                )

                "286002" -> defaultFor(mcc, mnc).copy(
                    // Vodafone TR rejects generic phone-context for short service
                    // codes such as 542 and wants explicit access-tech PANI.
                    keepShortServiceCodesAsPlainTel = true,
                    outgoingAccessTechPani = true,
                )

                "450006" -> defaultFor(mcc, mnc).copy(
                    // LG U+ can only do UDP and requires non-session AKA.
                    isControlSocketUdp = true,
                    requireNonsessAka = true,
                )

                "525001" -> defaultFor(mcc, mnc).copy(
                    // SingTel accepts REGISTER/SMS but needs a stock-like compact
                    // outgoing INVITE/SMS target/security shape.
                    singtelStockPolicy = true,
                )

                "208010" -> defaultFor(mcc, mnc).copy(
                    // 20810 can do TCP and UDP; use UDP for testing.
                    isControlSocketUdp = true,
                )

                else -> defaultFor(mcc, mnc)
            }
        }

        private fun defaultFor(mcc: String, mnc: String): SipCarrierSettings =
            SipCarrierSettings(
                mcc = mcc,
                mnc = mnc,
                isControlSocketUdp = false,
                requireNonsessAka = false,
            )
    }
}
