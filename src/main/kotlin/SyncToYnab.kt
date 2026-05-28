package com.github.davidsteinsland.ynab_psd2_sync

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

/**
 * Pusher transaksjoner direkte fra `extracted/<uid>.json` til YNAB uten å treffe
 * Enable Banking. Kobler EB-kontoer til YNAB-kontoer via mappings-fila.
 */
internal class SyncToYnab(
    val ynab: YnabClient,
    val mappingsStore: YnabMappingsStore,
    val objectMapper: ObjectMapper,
    private val ntfyTopic: String? = System.getenv("NTFY_TOPIC")?.takeUnless { it.isBlank() },
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
): Command {
    override fun run() {
        val mappings = mappingsStore.load()
        val budgetId = mappings.ynabBudgetId
        val transferPayees = buildTransferPayees(mappings)

        val extractedDir = File("extracted")
        if (!extractedDir.isDirectory) error("Mappa extracted/ finnes ikke")

        val perAccount = mutableListOf<Pair<String, List<Transaksjon>>>()

        mappings.mappings.forEach { mapping ->
            val file = File(extractedDir, "${mapping.accountUid}.json")
            if (!file.isFile) {
                log.warn("Hopper over {} – fant ikke {}", mapping.label ?: mapping.primaryAccountNumber, file.path)
                return@forEach
            }
            val transactions = objectMapper.readTree(file).path("transactions").toList()
            try {
                val created = pushTilYnab(ynab, budgetId, mapping, transactions, transferPayees)
                perAccount += mapping.primaryAccountNumber to created
            } catch (e: Exception) {
                log.error("  YNAB-push feilet: {}", e.message, e)
            }
        }

        notifyNtfy(mappings, perAccount)
    }

    private fun notifyNtfy(ynabMappings: YnabMappings, perAccount: List<Pair<String, List<Transaksjon>>>) {
        if (ntfyTopic.isNullOrBlank()) return
        if (perAccount.isEmpty()) return

        val total = perAccount.sumOf { it.second.size }
        if (total == 0) return

        notifyInflows(ynabMappings, perAccount)

        val title = "$total nye transaksjoner syncet"
        val body = perAccount
            .filter { it.second.isNotEmpty() }
            .joinToString(", ") { (label, count) -> "${count.size} nye for $label" }
        notify(title, listOf("tada"), body)
    }

    private fun notifyInflows(ynabMappings: YnabMappings, perAccount: List<Pair<String, List<Transaksjon>>>) {
        val today = LocalDate.now()
        val accountsWithInflow = ynabMappings
            .mappings
            .filter { it.monitorInflow }
            .mapNotNull { ynabAccount ->
                perAccount
                    .firstOrNull { it.first == ynabAccount.primaryAccountNumber }
                    ?.second
                    // bare bry oss om positive beløp
                    ?.filter { it.signedAmount > 0 }
                    // ikke bry oss om interne overføringer
                    ?.filter { it.transferPayeeId == null }
                    // bare bry oss om dagens dato
                    ?.filter { it.date == today }
                    ?.let { inflows ->
                        (ynabAccount.notificationName ?: ynabAccount.primaryAccountNumber) to inflows
                    }
            }
        if (accountsWithInflow.isEmpty()) return

        val numberFormat = NumberFormat.getCurrencyInstance(Locale.of("no", "NO")).apply {
            maximumFractionDigits = 2
            minimumFractionDigits = 0
        }
        val totalInflow = accountsWithInflow.sumOf { it.second.sumOf { it.signedAmount } }
        val title = "${numberFormat.format(totalInflow)} inn på konto i dag"
        val body = accountsWithInflow.joinToString(separator = "\n\n") { (label, amount) ->
            """
                $label:
                ${amount.joinToString(separator = "\n") { "${numberFormat.format(it.signedAmount)} fra ${it.payee}" } }
            """.trimIndent()
        }
        notify(title, listOf("moneybag", "money_mouth_face"), body)
    }

    private fun notify(title: String, tags: List<String>, body: String) {
        try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("https://ntfy.sh/$ntfyTopic"))
                .header("Title", title)
                .header("Tags", tags.joinToString(","))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding())
            if (resp.statusCode() !in 200..299) {
                log.warn("ntfy svarte med {} for sync-varsel", resp.statusCode())
            }
        } catch (e: Exception) {
            log.warn("Klarte ikke å sende ntfy sync-varsel: {}", e.message)
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
): List<Transaksjon> {
    val label = mapping.label ?: mapping.primaryAccountNumber
    log.info("Pusher {} ({}): {} transaksjoner", label, mapping.primaryAccountNumber, transactions.size)

    val parsed = transactions.mapNotNull { Transaksjon.fromNode(it, transferPayees) }

    val payload = parsed
        .mapNotNull { it.tilYnabApi(mapping.ynabAccountId) }
    val skipped = transactions.size - payload.size
    if (payload.isEmpty()) {
        log.info("  Ingen transaksjoner å pushe (hoppet over {} pending/før pushFrom/ugyldige)", skipped)
        return emptyList()
    }
    val data = ynab.createTransactions(budgetId, payload)
    val transactionsById = data
        .path("transactions")
        .values()
        .associateBy { it.path("id").asString() }

    // maps the created ynab transactions to the correct Transaction object
    // by using the importId as reference
    val created = data
        .path("transaction_ids")
        .values()
        .map {
            val importId = transactionsById.getValue(it.asString()).path("importId").asString()
            parsed.single { it.importId == importId}
        }

    val duplicates = data.path("duplicate_import_ids").size()
    log.info("  Pushet {} nye, {} duplikater hoppet over, {} hoppet over (pending/før pushFrom)", created, duplicates, skipped)
    return created
}

data class NewTransaction(
    val transactionId: String,
    val importId: String
)