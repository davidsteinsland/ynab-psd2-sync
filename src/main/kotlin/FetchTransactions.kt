package com.github.davidsteinsland.ynab_psd2_sync

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.convertValue
import java.io.File
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import kotlin.collections.orEmpty
import kotlin.collections.toSet
import kotlin.text.contains

internal class FetchTransactions(
    val client: EnableBankingClient,
    val stateStore: StateStore,
    val mappingsStore: YnabMappingsStore,
    val objectMapper: ObjectMapper,
    val expiryNotifier: SessionExpiryNotifier,
    val ntfyClient: NtfyClient?
): Command {
    override fun run() {
        val fetchTransactionsFrom = LocalDate.now().minusDays(7)
        val fetchTransactionsTo = LocalDate.now()

        val root = stateStore.loadRoot()
        if (root.sessions.isEmpty()) error("Ingen lagrede sesjoner. Kjør med --init først.")

        expiryNotifier.check(root.sessions)

        val mappings = mappingsStore.loadOrNull()?.mappings
        if (mappings.isNullOrEmpty()) {
            log.warn("Ingen mapping i {} – henter ingen kontoer. Kjør --map-accounts først.", mappingsStore.path)
            return
        }

        val extractedDir = File("extracted").also { it.mkdirs() }

        val failures = mutableListOf<String>()
        root.sessions.forEach { sessionState ->
            log.info("Henter fra {} (sesjon {})", sessionState.aspspName, sessionState.sessionId)
            try {
                hentFraSesjon(
                    client = client,
                    sessionState = sessionState,
                    mappings = mappings,
                    dateFrom = fetchTransactionsFrom,
                    dateTo = fetchTransactionsTo,
                    extractedDir = extractedDir,
                )
            } catch (e: Exception) {
                log.error("Feil for {}: {}", sessionState.aspspName, e.message, e)
                failures += "${sessionState.aspspName}: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) {
            error("Feil under henting for ${failures.size} sesjon(er): ${failures.joinToString("; ")}")
        }
    }

    private fun hentFraSesjon(
        client: EnableBankingClient,
        sessionState: SessionState,
        mappings: List<AccountMapping>,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        extractedDir: File
    ) {
        if (sessionState.accounts.isEmpty()) {
            error("Ingen kontoer lagret for ${sessionState.aspspName}. Kjør --init på nytt.")
        }

        val syncableAccounts = sessionState.accounts
            .mapNotNull { account -> mappings.firstOrNull { it.accountUid == account.uid } }

        log.info("  {} konto(er) (av totalt {}) for perioden {} til {}", syncableAccounts.size, sessionState.accounts.size, dateFrom, dateTo)

        val newTransactions = syncableAccounts
            .map { account ->
                val transactions = client.getAllTransactions(account.accountUid, dateFrom, dateTo)
                val resultFile = File(extractedDir, "${account.accountUid}.json")
                val newTransactions = resultFile.writeTransactionsToFile(sessionState.aspspName, transactions)
                account to newTransactions
            }

        notifyNewTransactions(newTransactions)
    }

    private fun File.writeTransactionsToFile(aspspName: String, transactions: List<JsonNode>): List<TransactionDto> {
        val seenTransactions = if (isFile)
            objectMapper
                .readTree(this)
                .path("transactions")
                .asArray()
                .let { objectMapper.convertValue<List<TransactionDto>>(it) }
                .map { it.fingerprint }
        else emptyList()

        writeText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
            mapOf("aspsp" to aspspName, "transactions" to transactions)
        ))

        return objectMapper.convertValue<List<TransactionDto>>(transactions)
            .filterNot { it.fingerprint in seenTransactions }
    }

    private fun notifyNewTransactions(newTransactions: List<Pair<AccountMapping, List<TransactionDto>>>) {
        if (ntfyClient == null) return
        val today = LocalDate.now()
        val accountsWithInflow = newTransactions
            .filter { it.first.monitorInflow }
            .mapNotNull { (account, transactions) ->
                transactions
                    .asSequence()
                    .filter { it.valueDate == today || it.bookingDate == today || it.transactionDate == today }
                    .filter { it.creditDebitIndicator == CreditDebitIndicatorDto.CRDT }
                    .filter { (it.transactionAmount.amountAsDouble ?: 0.0) > 0.0 }
                    // try to exclude transfers between accounts
                    .filter { it.bankTransactionCode?.code !in listOf("200", "739") }
                    .toList()
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        account to it
                    }
            }

        if (accountsWithInflow.isEmpty()) return log.info("ingen inflows å varsle")

        val numberFormat = NumberFormat.getCurrencyInstance(Locale.of("no", "NO")).apply {
            maximumFractionDigits = 2
            minimumFractionDigits = 0
        }
        val totalInflow = accountsWithInflow.sumOf { it.second.sumOf { it.transactionAmount.amountAsDouble!! } }
        val title = "${numberFormat.format(totalInflow)} inn på konto"
        val body = accountsWithInflow.joinToString(separator = "\n\n") { (label, txs) ->
            """
                ${label.notificationName}:
                ${txs.joinToString(separator = "\n") {
                    val avsender = it.debtor?.name ?: "${it.debtorAccount?.other?.identification} (${it.remittanceInformation.joinToString()})"
                    "${numberFormat.format(it.transactionAmount.amountAsDouble)} fra $avsender" 
                } }
            """.trimIndent()
        }
        ntfyClient.notify(title, listOf("moneybag", "money_mouth_face"), body)
    }
}
