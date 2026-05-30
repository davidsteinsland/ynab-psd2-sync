package com.github.davidsteinsland.ynab_psd2_sync

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Sjekker hver sesjons `validUntil` og sender et ntfy-varsel hvis det er færre enn
 * [warningDays] dager igjen. Brukes til å minne om at PSD2-samtykket (180 dager) snart utløper.
 *
 * Skipper varslingen stille hvis [ntfyClient] er null — da brukes kun logging.
 */
internal class SessionExpiryNotifier(
    private val ntfyClient: NtfyClient?,
    private val warningDays: Long,
) {
    private val log = LoggerFactory.getLogger(SessionExpiryNotifier::class.java)

    fun check(sessions: List<SessionState>) {
        val now = Instant.now()
        for (session in sessions) {
            val daysLeft = ChronoUnit.DAYS.between(now, session.validUntil)
            when {
                daysLeft < 0 -> notify(session, daysLeft, expired = true)
                daysLeft <= warningDays -> notify(session, daysLeft, expired = false)
                else -> log.debug("Sesjon {} har {} dager igjen", session.aspspName, daysLeft)
            }
        }
    }

    private fun notify(session: SessionState, daysLeft: Long, expired: Boolean) {
        val msg = if (expired) {
            "Sesjon for ${session.aspspName} (${session.aspspCountry}) er UTLØPT. Kjør --init på nytt."
        } else {
            "Sesjon for ${session.aspspName} (${session.aspspCountry}) utløper om $daysLeft dager. Kjør --init før den ryker."
        }
        log.warn(msg)
        ntfyClient?.notify(
            title = if (expired) "❌ Bank-sesjon utløpt" else "⚠️ Bank-sesjon utløper snart",
            tags = listOf("warning","bank"),
            body = msg,
            priority = if (expired) NtfyClient.Priority.HIGH else NtfyClient.Priority.DEFAULT,
        )
    }
}
