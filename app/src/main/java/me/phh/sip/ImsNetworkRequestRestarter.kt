// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.Rlog
import android.telephony.TelephonyManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class ImsNetworkRequestRestarter(
    private val tag: String,
    private val telephonyManager: TelephonyManager,
    private val requestImsNetwork: () -> Unit,
) {
    private val scheduled = AtomicBoolean(false)

    fun schedule(reason: String, initialDelayMs: Long = 12_000L) {
        if (!scheduled.compareAndSet(false, true)) {
            Rlog.w(tag, "IMS network request restart already scheduled, ignore: $reason")
            return
        }

        thread {
            try {
                var delayMs = initialDelayMs
                while (true) {
                    Rlog.w(tag, "Will request IMS network after ${delayMs}ms if RAT is IMS-capable: $reason")
                    Thread.sleep(delayMs)
                    if (ImsNetworkState.isRatReadyForImsNetworkRequest(tag, telephonyManager)) {
                        Rlog.w(tag, "Re-requesting IMS network after RAT recovered: $reason")
                        requestImsNetwork()
                        return@thread
                    }
                    delayMs = 5_000L
                }
            } catch (t: Throwable) {
                Rlog.e(tag, "IMS network request restart failed: $reason", t)
            } finally {
                scheduled.set(false)
            }
        }
    }
}
