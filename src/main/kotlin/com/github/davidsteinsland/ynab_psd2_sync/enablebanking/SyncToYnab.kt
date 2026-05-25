package com.github.davidsteinsland.ynab_psd2_sync.enablebanking

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.File

/**
 * Pusher transaksjoner direkte fra `extracted/<uid>.json` til YNAB uten å treffe
 * Enable Banking. Kobler EB-kontoer til YNAB-kontoer via mappings-fila.
 */
internal class SyncToYnab(
    val ynab: YnabClient,
    val mappingsStore: YnabMappingsStore,
    val objectMapper: ObjectMapper,
): Command {
    override fun run() {
        val mappings = mappingsStore.load()
        val budgetId = mappings.ynabBudgetId
        val transferPayees = buildTransferPayees(mappings)

        val extractedDir = File("extracted")
        if (!extractedDir.isDirectory) error("Mappa extracted/ finnes ikke")

        mappings.mappings.forEach { mapping ->
            val file = File(extractedDir, "${mapping.accountUid}.json")
            if (!file.isFile) {
                log.warn("Hopper over {} – fant ikke {}", mapping.label ?: mapping.primaryAccountNumber, file.path)
                return@forEach
            }
            val transactions = objectMapper.readTree(file).path("transactions").toList()
            try {
                pushTilYnab(ynab, budgetId, mapping, transactions, transferPayees)
            } catch (e: Exception) {
                log.error("  YNAB-push feilet: {}", e.message, e)
            }
        }
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
    transactions: List<JsonNode>,
    transferPayees: Map<String, String>,
) {
    val label = mapping.label ?: mapping.primaryAccountNumber
    log.info("Pusher {} ({}): {} transaksjoner", label, mapping.primaryAccountNumber, transactions.size)

    val parsed = transactions.mapNotNull { Transaksjon.fromNode(it, transferPayees) }

    val payload = parsed
        .mapNotNull { it.tilYnabApi(mapping.ynabAccountId) }
    val skipped = transactions.size - payload.size
    if (payload.isEmpty()) {
        log.info("  Ingen transaksjoner å pushe (hoppet over {} pending/før pushFrom/ugyldige)", skipped)
        return
    }
    val data = ynab.createTransactions(budgetId, payload)
    val created = data.path("transaction_ids").size()
    val duplicates = data.path("duplicate_import_ids").size()
    log.info("  Pushet {} nye, {} duplikater hoppet over, {} hoppet over (pending/før pushFrom)", created, duplicates, skipped)
}