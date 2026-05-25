package com.github.davidsteinsland.ynab_psd2_sync.enablebanking

import tools.jackson.databind.ObjectMapper
import java.io.File

/**
 * Mapper Enable Banking-kontoer til YNAB-kontoer. Leser EB-kontoer fra
 * `extracted/<uid>.json` (skrevet av --fetch). Lagrer resultatet i mappings-fila
 * (typisk `.ynab.json`) som er delt på tvers av brukere/state-filer.
 */
internal class MapAccounts(
    val ynab: YnabClient,
    val mappingsStore: YnabMappingsStore,
    val objectMapper: ObjectMapper,
): Command {
    override fun run() {
        val extractedDir = File("extracted")
        if (!extractedDir.isDirectory) error("Mappa extracted/ finnes ikke. Kjør fetch først.")
        val ebAccounts = extractedDir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { file ->
                val node = objectMapper.readTree(file)
                val account = node.path("account")
                val uid = account.path("uid").asString().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val primary = mapAccountNumber(account.path("account_id").path("other")) ?: return@mapNotNull null
                EbAccount(
                    uid = uid,
                    primaryAccountNumber = primary.accountNumber,
                    name = account.path("name").stringValue() ?: "",
                    aspsp = node.path("aspsp").stringValue() ?: "",
                )
            }
            .distinctBy { it.uid }
            .sortedWith(compareBy({ it.aspsp }, { it.primaryAccountNumber }))

        if (ebAccounts.isEmpty()) error("Ingen Enable Banking-kontoer funnet i extracted/. Kjør fetch først.")

        val existing = mappingsStore.loadOrNull()
        val budgetId = existing?.ynabBudgetId ?: run {
            val budgets = ynab.getBudgets().toList()
            if (budgets.isEmpty()) error("Ingen YNAB-budsjetter funnet")
            println("YNAB-budsjetter:")
            budgets.forEachIndexed { idx, b -> println("  [$idx] ${b.path("name").asString()} (id=${b.path("id").asString()})") }
            print("Velg nummer: ")
            budgets[readln().trim().toInt()].path("id").asString()
        }

        val ynabAccounts = ynab.getAccounts(budgetId)
            .filterNot { it.path("closed").asBoolean() || it.path("deleted").asBoolean() }
            .toList()

        val existingByUid = existing?.mappings?.associateBy { it.accountUid }.orEmpty()

        val updatedMappings = ebAccounts.mapNotNull { eb ->
            val prior = existingByUid[eb.uid]
            val ynabAcc = if (prior == null) {
                println()
                println("${eb.aspsp} / ${eb.primaryAccountNumber} (${eb.name}) – velg YNAB-konto:")
                println("  [-1] hopp over")
                ynabAccounts.forEachIndexed { idx, a ->
                    println(
                        "  [$idx] ${a.path("name").asString()} (${
                            a.path("type").asString()
                        }, balanse ${a.path("balance").asLong() / 1000.0})"
                    )
                }
                print("Velg nummer: ")
                val choice = readln().trim().toIntOrNull() ?: -1
                ynabAccounts.getOrNull(choice) ?: return@mapNotNull null
            } else {
                println("✓ ${eb.aspsp} / ${eb.primaryAccountNumber} (${eb.name}) → allerede mappet")
                ynabAccounts.singleOrNull { it.path("id").asString() == prior.ynabAccountId } ?: return@mapNotNull prior
            }

            AccountMapping(
                accountUid = eb.uid,
                primaryAccountNumber = eb.primaryAccountNumber,
                ynabAccountId = ynabAcc.path("id").asString(),
                ynabTransferPayeeId = ynabAcc.path("transfer_payee_id").stringValue(),
                label = "${eb.aspsp} – ${eb.name.ifBlank { eb.primaryAccountNumber }}",
            )
        }

        mappingsStore.save(YnabMappings(ynabBudgetId = budgetId, mappings = updatedMappings))
        log.info("Mapping lagret ({} kontoer).", updatedMappings.size)
    }

    private data class EbAccount(
        val uid: String,
        val primaryAccountNumber: String,
        val name: String,
        val aspsp: String,
    )
}