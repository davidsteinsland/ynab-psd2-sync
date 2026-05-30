package com.github.davidsteinsland.ynab_psd2_sync

import java.security.MessageDigest
import java.time.LocalDate
import java.util.Base64
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
    val memo: String,
    val booked: Boolean,
    val fingerprint: String,
    val occurrence: Int = 1
) {
    val milliunits = (signedAmount * 1000.0).roundToLong()

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
        // YNAB import_id må være <= 36 tegn
        val importId = "$fingerprint:$occurrence".let { payload ->
            val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
            b64.take(36)
        }
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
        fun fromDto(
            node: TransactionDto,
            transferPayees: Map<String, String>
        ): Transaksjon? {
            val possibleDates = listOfNotNull(node.bookingDate, node.valueDate, node.transactionDate)
            val date = possibleDates.minOrNull() ?: return null

            val amount = node.transactionAmount.amountAsDouble ?: return null

            val (signedAmount, payee, counterpartyBban) = when (node.creditDebitIndicator) {
                CreditDebitIndicatorDto.DBIT -> {
                    // penger går ut av konto
                    val signedAmount = -abs(amount)
                    Triple(signedAmount, node.creditor?.name, node.creditorAccount?.other?.identification)
                }
                CreditDebitIndicatorDto.CRDT -> {
                    // penger kommer inn på konto
                    val signedAmount = abs(amount)
                    Triple(signedAmount, node.debtor?.name, node.debtorAccount?.other?.identification)
                }
            }

            // har sett tilfeller med banknorwegian at teksten "Cashback transfer" kommer to ganger
            val memo = node.remittanceInformation
                .distinct()
                .joinToString(" ")

            val isBooked = node.status == StatusDto.BOOK

            val transferPayeeId = counterpartyBban?.let { transferPayees[normalizeBban(it)] }

            return Transaksjon(
                date = date,
                signedAmount = signedAmount,
                transferPayeeId = transferPayeeId,
                payee = payee,
                memo = memo,
                booked = isBooked,
                fingerprint = node.fingerprint,
            )
        }

        fun List<Transaksjon>.withOccurrenceCounter(): List<Transaksjon> {
            val sorted = sortedByDescending { it.date }
            return sorted.mapIndexed { index, tx ->
                val similar = sorted
                    .take(index)
                    .count { it.fingerprint == tx.fingerprint }
                tx.copy(occurrence = similar + 1)
            }
        }

        /** Norske BBAN er 11 siffer; banker formaterer dem ulikt (mellomrom, punktum, dash). */
        fun normalizeBban(bban: String): String = bban.filter { it.isDigit() }
    }
}