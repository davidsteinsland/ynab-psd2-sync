package com.github.davidsteinsland.ynab_psd2_sync.enablebanking

import tools.jackson.databind.ObjectMapper
import java.io.File
import java.time.LocalDate

internal class FetchTransactions(
    val client: EnableBankingClient,
    val stateStore: StateStore,
    val mappingsStore: YnabMappingsStore,
    val objectMapper: ObjectMapper,
): Command {
    override fun run() {
        val fetchTransactionsFrom = LocalDate.now().minusDays(7)
        val fetchTransactionsTo = LocalDate.now()

        val root = stateStore.loadRoot()
        if (root.sessions.isEmpty()) error("Ingen lagrede sesjoner. Kjør med --init først.")

        val mappedUids = mappingsStore.loadOrNull()?.mappings?.map { it.accountUid }?.toSet().orEmpty()
        if (mappedUids.isEmpty()) {
            log.warn("Ingen mapping i {} – henter ingen kontoer. Kjør --map-accounts først.", mappingsStore.path)
            return
        }

        val extractedDir = File("extracted").also { it.mkdirs() }

        root.sessions.forEach { sessionState ->
            log.info("Henter fra {} (sesjon {})", sessionState.aspspName, sessionState.sessionId)
            try {
                hentFraSesjon(
                    client = client,
                    sessionState = sessionState,
                    mappedUids = mappedUids,
                    objectMapper = objectMapper,
                    dateFrom = fetchTransactionsFrom,
                    dateTo = fetchTransactionsTo,
                    extractedDir = extractedDir,
                )
            } catch (e: Exception) {
                log.error("Feil for {}: {}", sessionState.aspspName, e.message, e)
            }
        }
    }
}

private fun hentFraSesjon(
    client: EnableBankingClient,
    sessionState: SessionState,
    mappedUids: Set<String>,
    objectMapper: ObjectMapper,
    dateFrom: LocalDate,
    dateTo: LocalDate,
    extractedDir: File
) {
    if (sessionState.accounts.isEmpty()) {
        error("Ingen kontoer lagret for ${sessionState.aspspName}. Kjør --init på nytt.")
    }
    val kontoer = sessionState.accounts.filter { it.uid in mappedUids }

    log.info("  {} konto(er) (av totalt {}) for perioden {} til {}", kontoer.size, sessionState.accounts.size, dateFrom, dateTo)

    kontoer.forEach { account ->
        val transactions = client.getAllTransactions(account.uid, dateFrom, dateTo)
        File(extractedDir, "${account.uid}.json")
            .writeText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                mapOf("aspsp" to sessionState.aspspName, "account" to account.details, "transactions" to transactions)
            ))
    }
}