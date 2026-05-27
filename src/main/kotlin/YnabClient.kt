package com.github.davidsteinsland.ynab_psd2_sync

import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Klient for YNAB API.
 * https://api.ynab.com/
 */
class YnabClient(
    private val accessToken: String,
    private val objectMapper: ObjectMapper,
    private val baseUrl: String = "https://api.ynab.com/v1",
    private val client: HttpClient = HttpClient.newHttpClient(),
) {
    private companion object {
        private val log = LoggerFactory.getLogger(this::class.java)
    }

    fun getBudgets(): ArrayNode = request("GET", "/budgets").path("data").path("budgets").asArray()

    fun getAccounts(budgetId: String): ArrayNode =
        request("GET", "/budgets/$budgetId/accounts").path("data").path("accounts").asArray()

    /**
     * Bulk-oppretter transaksjoner. YNAB matcher på `import_id` og hopper over duplikater.
     * Returnerer responsdata med `transactions`, `duplicate_import_ids`, og `transaction_ids`.
     */
    fun createTransactions(budgetId: String, transactions: List<Map<String, Any?>>): JsonNode {
        val body = objectMapper.writeValueAsString(mapOf("transactions" to transactions))
        return request("POST", "/budgets/$budgetId/transactions", body).path("data")
    }

    private fun request(method: String, path: String, body: String? = null): JsonNode {
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val builder = HttpRequest.newBuilder()
            .method(method, publisher)
            .uri(URI.create("$baseUrl$path"))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
        if (body != null) builder.header("Content-Type", "application/json")

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        log.info("{} {}: {}", method, path, response.statusCode())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("YNAB-feil ${response.statusCode()} på $method $path: ${response.body()}")
        }
        return objectMapper.readTree(response.body())
    }
}
