package com.github.davidsteinsland.ynab_psd2_sync

import com.github.davidsteinsland.ynab_psd2_sync.Transaksjon.Companion.withOccurrenceCounter
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.convertValue
import java.io.File

/**
 * Pusher transaksjoner direkte fra `extracted/<uid>.json` til YNAB uten å treffe
 * Enable Banking. Kobler EB-kontoer til YNAB-kontoer via mappings-fila.
 */
internal class SyncToYnab(
    val ynab: YnabClient,
    val mappingsStore: YnabMappingsStore,
    val objectMapper: ObjectMapper,
    private val ntfyClient: NtfyClient?,
): Command {
    override fun run() {
        val mappings = mappingsStore.load()
        val budgetId = mappings.ynabBudgetId
        val transferPayees = buildTransferPayees(mappings)

        val extractedDir = File("extracted")
        if (!extractedDir.isDirectory) error("Mappa extracted/ finnes ikke")

        val perAccount = mutableListOf<Pair<String, Int>>()

        mappings.mappings.forEach { mapping ->
            val file = File(extractedDir, "${mapping.accountUid}.json")
            if (!file.isFile) {
                log.warn("Hopper over {} – fant ikke {}", mapping.label ?: mapping.primaryAccountNumber, file.path)
                return@forEach
            }
            val transactions = objectMapper.convertValue<List<TransactionDto>>(objectMapper.readTree(file).path("transactions").toList())
            val created = pushTilYnab(ynab, budgetId, mapping, transactions, transferPayees)
            perAccount += mapping.primaryAccountNumber to created
        }

        notifyNtfy(perAccount)
    }

    private fun notifyNtfy(perAccount: List<Pair<String, Int>>) {
        if (ntfyClient == null) return
        if (perAccount.isEmpty()) return

        val total = perAccount.sumOf { it.second }
        if (total == 0) return

        val title = "$total nye transaksjoner syncet"
        val body = perAccount
            .filter { it.second > 0 }
            .joinToString(", ") { (label, count) -> "$count nye for $label" }
        ntfyClient.notify(title, listOf("tada"), body)
    }
}

/** Bygger map fra normalisert BBAN -> YNAB transfer_payee_id på tvers av alle mappinger. */
private fun buildTransferPayees(mappings: YnabMappings): Map<String, String> =
    mappings.mappings
        .mapNotNull { m -> m.ynabTransferPayeeId?.let { Transaksjon.normalizeBban(m.primaryAccountNumber) to it } }
        .toMap()

/**
 * Pusher en liste rå transaksjon-noder til YNAB. Filtrerer bort pending og
 * (valgfritt) transaksjoner før `pushFrom`.
 */
private fun pushTilYnab(
    ynab: YnabClient,
    budgetId: String,
    mapping: AccountMapping,
    transactions: List<TransactionDto>,
    transferPayees: Map<String, String>,
): Int {
    val label = mapping.label ?: mapping.primaryAccountNumber
    log.info("Pusher {} ({}): {} transaksjoner", label, mapping.primaryAccountNumber, transactions.size)

    val parsed = transactions
        .mapNotNull { Transaksjon.fromDto(it, transferPayees) }
        .withOccurrenceCounter()

    val payload = parsed
        .mapNotNull { it.tilYnabApi(mapping.ynabAccountId) }
    val skipped = transactions.size - payload.size
    if (payload.isEmpty()) {
        log.info("  Ingen transaksjoner å pushe (hoppet over {} pending/før pushFrom/ugyldige)", skipped)
        return 0
    }
    val data = ynab.createTransactions(budgetId, payload)

    val created = data
        .path("transaction_ids")
        .size()

    val duplicates = data.path("duplicate_import_ids").size()
    log.info("  Pushet {} nye, {} duplikater hoppet over, {} hoppet over (pending/før pushFrom)", created, duplicates, skipped)
    return created
}
