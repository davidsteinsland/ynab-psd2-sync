package com.github.davidsteinsland.ynab_psd2_sync

import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import kotlin.jvm.optionals.getOrNull

/**
 * Klient for Enable Banking API.
 * https://enablebanking.com/docs/
 */
class EnableBankingClient(
    private val applicationId: String,
    privateKeyPem: String,
    private val objectMapper: ObjectMapper,
    private val baseUrl: String = "https://api.enablebanking.com",
    private val client: HttpClient = HttpClient.newHttpClient(),
) {
    private companion object {
        private val log = LoggerFactory.getLogger(EnableBankingClient::class.java)
        private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    }

    private val privateKey = run {
        val cleaned = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace(Regex("\\s+"), "")
        val keyBytes = Base64.getDecoder().decode(cleaned)
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
    }

    /**
     * Bygger JWT (RS256) brukt som Bearer-token mot Enable Banking API.
     * JWT er gyldig i 1 time og brukes som autentisering for alle kall.
     */
    fun buildJwt(): String {
        val now = Instant.now().epochSecond
        val header = """{"typ":"JWT","alg":"RS256","kid":"$applicationId"}"""
        val body = """{"iss":"enablebanking.com","aud":"api.enablebanking.com","iat":$now,"exp":${now + 3600}}"""

        val signingInput = urlEncoder.encodeToString(header.toByteArray(StandardCharsets.UTF_8)) +
            "." + urlEncoder.encodeToString(body.toByteArray(StandardCharsets.UTF_8))

        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(signingInput.toByteArray(StandardCharsets.UTF_8))
        }.sign()

        return "$signingInput." + urlEncoder.encodeToString(sig)
    }

    fun application(): JsonNode = request("GET", "/application")

    /** Liste over banker. Filter på land (ISO 3166 alpha-2, f.eks. "NO"). */
    fun aspsps(country: String? = null): JsonNode {
        val q = if (country != null) "?country=$country" else ""
        return request("GET", "/aspsps$q")
    }

    /**
     * Starter samtykkeflyt. Returnerer JSON med `url` som sluttbruker må besøke.
     */
    fun startAuth(
        aspspName: String,
        aspspCountry: String,
        validUntil: Instant,
        redirectUrl: String,
        state: String,
        psuType: String = "personal",
    ): JsonNode {
        val body = objectMapper.writeValueAsString(
            mapOf(
                "access" to mapOf("valid_until" to validUntil.toString()),
                "aspsp" to mapOf("name" to aspspName, "country" to aspspCountry),
                "state" to state,
                "redirect_url" to redirectUrl,
                "psu_type" to psuType,
            )
        )
        return request("POST", "/auth", body)
    }

    /** Bytter `code` fra redirect-URL mot en brukersesjon med kontoliste. */
    fun createSession(authCode: String): JsonNode {
        val body = objectMapper.writeValueAsString(mapOf("code" to authCode))
        return request("POST", "/sessions", body)
    }

    fun getSession(sessionId: String): JsonNode = request("GET", "/sessions/$sessionId")

    fun deleteSession(sessionId: String): JsonNode = request("DELETE", "/sessions/$sessionId")

    fun getBalances(accountUid: String): JsonNode =
        request("GET", "/accounts/$accountUid/balances", psuHeaders = defaultPsuHeaders())

    fun getAccountDetails(accountUid: String): JsonNode =
        request("GET", "/accounts/$accountUid/details", psuHeaders = defaultPsuHeaders())

    /**
     * Henter transaksjoner. Bruker continuation_key til å pagine gjennom alle sider.
     */
    fun getAllTransactions(accountUid: String, dateFrom: LocalDate, dateTo: LocalDate? = null): List<JsonNode> {
        val all = mutableListOf<JsonNode>()
        var continuationKey: String? = null
        do {
            val params = buildList {
                add("date_from=$dateFrom")
                if (dateTo != null) add("date_to=$dateTo")
                if (continuationKey != null) add("continuation_key=${URLEncoder.encode(continuationKey, StandardCharsets.UTF_8)}")
            }.joinToString("&")
            val page = request("GET", "/accounts/$accountUid/transactions?$params", psuHeaders = defaultPsuHeaders())
            page.path("transactions").forEach { all.add(it) }
            continuationKey = page.path("continuation_key").asStringOpt()?.getOrNull()
        } while (continuationKey != null)
        return all
    }

    /**
     * PSU-headere kreves av enkelte norske banker (Bank Norwegian, Sparebanken Vest)
     * på account-data-endepunkter, selv utenfor SCA-vinduet.
     * IP-adressen hentes via ENABLEBANKING_PSU_IP_ADDRESS, eller via offentlig oppslag (ifconfig.me).
     * User-Agent kan overstyres via ENABLEBANKING_PSU_USER_AGENT.
     */
    private val resolvedPsuIp: String by lazy {
        System.getenv("ENABLEBANKING_PSU_IP_ADDRESS")?.takeIf { it.isNotBlank() } ?: lookupPublicIp()
    }

    private fun lookupPublicIp(): String {
        val req = HttpRequest.newBuilder()
            .GET()
            .uri(URI.create("https://ifconfig.me/ip"))
            .header("User-Agent", "curl/8")
            .build()
        return runCatching {
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            require(resp.statusCode() in 200..299) { "ifconfig.me returnerte ${resp.statusCode()}" }
            resp.body().trim().also { require(it.isNotBlank()) { "tom respons" } }
        }.getOrElse {
            error("Klarte ikke å finne offentlig IP-adresse (${it.message}). Sett ENABLEBANKING_PSU_IP_ADDRESS manuelt.")
        }
    }

    private fun defaultPsuHeaders(): Map<String, String> = mapOf(
        "Psu-Ip-Address" to resolvedPsuIp,
        "Psu-User-Agent" to (System.getenv("ENABLEBANKING_PSU_USER_AGENT") ?: "ynab-transactions/1.0"),
    )

    private fun request(method: String, path: String, body: String? = null, psuHeaders: Map<String, String> = emptyMap()): JsonNode {
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val builder = HttpRequest.newBuilder()
            .method(method, publisher)
            .uri(URI.create("$baseUrl$path"))
            .header("Authorization", "Bearer ${buildJwt()}")
            .header("Accept", "application/json")
        if (body != null) builder.header("Content-Type", "application/json")
        psuHeaders.forEach { (k, v) -> builder.header(k, v) }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        log.info("{} {}: {}", method, path, response.statusCode())

        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Enable Banking-feil ${response.statusCode()} på $method $path: ${response.body()}")
        }
        return objectMapper.readTree(response.body())
    }
}
