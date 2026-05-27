package com.github.davidsteinsland.ynab_psd2_sync

import tools.jackson.databind.JsonNode
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Base64
import kotlin.jvm.optionals.getOrNull
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Berlin Group / Enable Banking transaction-format.
 * https://enablebanking.com/docs/api/reference/#transaction
 *
 * Felles representasjon brukt både av CSV-eksport og YNAB-API-push.
 */
internal data class Transaksjon(
    val date: LocalDate,
    /** Signert beløp i kroner. Negativt = utbetaling. */
    val signedAmount: Double,
    /** ID til intern ynab-konto dersom det er overføring mellom egne kontoer */
    val transferPayeeId: String?,
    /** Visningsnavn på motpart: creditor/debtor `name` med BBAN som fallback. */
    val payee: String?,
    /** Rå BBAN/identifikasjon fra creditor_account/debtor_account.other.identification. */
    val counterpartyBban: String?,
    val memo: String,
    val booked: Boolean,
    val entryReference: String?,
) {
    val milliunits = (signedAmount * 1000.0).roundToLong()
    val importId = entryReference?.let(::importIdFromEntryReference)
        ?: importIdFromContent(date, milliunits, payee, memo)

    /**
     * Returnerer null for pending – disse pushes ikke
     * fordi YNAB ikke automatisk oppdaterer cleared-status når de senere booker,
     * og innholdet (beløp/payee/memo) kan endre seg så content-hash gir duplikat.
     *
     * Hvis motpartens BBAN matcher en av brukerens egne kontoer (i transferPayees),
     * registreres transaksjonen som en intern overføring via `payee_id`. Ellers
     * brukes `payee_name`.
     */
    fun tilYnabApi(accountId: String): Map<String, Any?>? {
        if (!booked) return null
        return mapOf(
            "account_id" to accountId,
            "date" to date.toString(),
            "amount" to milliunits,
            // YNAB krever enten payee_id ELLER payee_name. Aldri begge.
            "payee_id" to transferPayeeId,
            "payee_name" to if (transferPayeeId == null) payee?.take(50) else null,
            "memo" to memo.takeIf { it.isNotBlank() }?.take(200),
            "cleared" to "cleared",
            "approved" to false,
            "import_id" to importId,
        )
    }

    companion object {
        /** Parser node fra Enable Banking. Returnerer null om dato/beløp mangler. */
        fun fromNode(
            node: JsonNode,
            transferPayees: Map<String, String>
        ): Transaksjon? {
            val possibleDates = listOfNotNull(
                node.path("booking_date").stringValue(),
                node.path("transaction_date").stringValue(),
                node.path("value_date").stringValue(),
            )
            val dateStr = possibleDates.minOrNull() ?: return null

            val amount = node.path("transaction_amount").path("amount").asString().toDoubleOrNull() ?: return null

            val amountAndCounterparty = when (val indicator = node.path("credit_debit_indicator").asString()) {
                "DBIT" -> {
                    // penger går ut av konto
                    val signedAmount = -abs(amount)
                    val counterpartyNode = node.path("creditor")
                    val counterpartyAccount = node.path("creditor_account")
                    AmountAndCounterparty(signedAmount, counterpartyNode, counterpartyAccount)
                }
                "CRDT" -> {
                    // penger kommer inn på konto
                    val signedAmount = abs(amount)
                    val counterpartyNode = node.path("debtor")
                    val counterpartyAccount = node.path("debtor_account")
                    AmountAndCounterparty(signedAmount, counterpartyNode, counterpartyAccount)
                }
                else -> error("Ukjent credit indicator: $indicator")
            }

            // har sett tilfeller med banknorwegian at teksten "Cashback transfer" kommer to ganger
            val memo = node.path("remittance_information")
                .asArrayOpt()
                .getOrNull()
                ?.values()
                ?.map(JsonNode::asString)
                ?.distinct()
                ?.joinToString(" ")
                ?: ""

            val status = node.path("status").asString().takeIf { it.isNotBlank() }
            val isBooked = status == "BOOK"

            val transferPayeeId = amountAndCounterparty.counterpartyBban?.let { transferPayees[normalizeBban(it)] }

            return Transaksjon(
                date = LocalDate.parse(dateStr),
                signedAmount = amountAndCounterparty.signedAmount,
                transferPayeeId = transferPayeeId,
                payee = amountAndCounterparty.payee,
                counterpartyBban = amountAndCounterparty.counterpartyBban,
                memo = memo,
                booked = isBooked,
                entryReference = node.path("entry_reference").stringValue()?.takeIf { it.isNotBlank() },
            )
        }

        /** Norske BBAN er 11 siffer; banker formaterer dem ulikt (mellomrom, punktum, dash). */
        fun normalizeBban(bban: String): String = bban.filter { it.isDigit() }

        private fun JsonNode.textOrNullIfBlank(): String? = stringValueOpt()?.getOrNull()?.takeUnless { it.isBlank() }

        /**
         * YNAB import_id må være <= 36 tegn. Hvis entry_reference er kortere brukes den direkte
         * (prefikset `EB:`), ellers SHA-256-hashes den til 33 tegn base64url + `EB:` prefiks (= 36).
         */
        private fun importIdFromEntryReference(entryReference: String): String {
            val candidate = "EB:$entryReference"
            if (candidate.length <= 36) return candidate
            val digest = MessageDigest.getInstance("SHA-256").digest(entryReference.toByteArray(Charsets.UTF_8))
            val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
            return ("EB:$b64").take(36)
        }

        /**
         * Stabil import-ID basert på transaksjonsinnhold. Brukes når ASPSP-en ikke gir
         * `entry_reference`. Endrer seg ikke om transaksjonen flyttes i lista mellom hentinger,
         * så lenge dato, beløp, payee og memo er uendret.
         */
        private fun importIdFromContent(date: LocalDate, milliunits: Long, payee: String?, memo: String?): String {
            val payload = "$date|$milliunits|${payee.orEmpty()}|${memo.orEmpty()}"
            val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
            return ("EBH:$b64").take(36)
        }

        private data class AmountAndCounterparty(
            val signedAmount: Double,
            val counterpartyNode: JsonNode,
            val counterpartyAccount: JsonNode
        ) {
            val counterpartyBban = counterpartyAccount.path("other").path("identification").textOrNullIfBlank()
            val payee = counterpartyNode.path("name").textOrNullIfBlank() ?: counterpartyBban
        }
    }
}