package com.github.davidsteinsland.ynab_psd2_sync.enablebanking

import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.io.File

/**
 * Konfigurasjonsfil som binder Enable Banking-kontoer til YNAB-kontoer.
 * Delt på tvers av brukere/state-filer fordi budsjettet er felles. Filen er
 * ikke versjonskontrollert (inneholder personlige YNAB-UUID-er).
 */
internal data class YnabMappings(
    val ynabBudgetId: String,
    val mappings: List<AccountMapping> = emptyList(),
)

internal data class AccountMapping(
    /** Enable Banking account uid (samme som `account.uid` i extracted/<uid>.json). */
    val accountUid: String,
    /** Primær BBAN/identifikasjon – brukes for transfer-detection. */
    val primaryAccountNumber: String,
    val ynabAccountId: String,
    /** YNAB transfer_payee_id for å oppdage interne overføringer mellom egne kontoer. */
    val ynabTransferPayeeId: String?,
    /** Valgfri etikett til menneskelig lesbarhet. */
    val label: String? = null,
)

internal class YnabMappingsStore(private val file: File, private val objectMapper: ObjectMapper) {
    val path: String get() = file.absolutePath

    fun loadOrNull(): YnabMappings? = if (file.exists()) objectMapper.readValue<YnabMappings>(file) else null

    fun load(): YnabMappings = loadOrNull()
        ?: error("Ingen mapping-fil på $path. Kjør --map-accounts først.")

    fun save(mappings: YnabMappings) {
        file.writeText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(mappings))
        log.info("Lagret {} mapping(er) i {}", mappings.mappings.size, file)
    }
}
