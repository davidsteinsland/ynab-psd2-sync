package com.github.davidsteinsland.ynab_psd2_sync

import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.convertValue
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

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
            val response = request("GET", "/accounts/$accountUid/transactions?$params", psuHeaders = defaultPsuHeaders())
            val page = objectMapper.convertValue<TransactionResponse>(response)
            page.transactions.forEach { all.add(it) }
            continuationKey = page.continuationKey
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

private data class TransactionResponse(
    val transactions: List<JsonNode>,
    @param:JsonProperty("continuation_key")
    val continuationKey: String?
)

data class TransactionDto(
    @param:JsonProperty("entry_reference")
    val entryReference: String?,
    @param:JsonProperty("transaction_amount")
    val transactionAmount: AmountDto,
    val creditor: CreditorDto?,
    @param:JsonProperty("creditor_account")
    val creditorAccount: CreditorAccountDto?,
    val debtor: DebtorDto?,
    @param:JsonProperty("debtor_account")
    val debtorAccount: DebtorAccountDto?,
    @param:JsonProperty("bank_transaction_code")
    val bankTransactionCode: BankTransactionCodeDto?,
    @param:JsonProperty("credit_debit_indicator")
    val creditDebitIndicator: CreditDebitIndicatorDto,
    val status: StatusDto,
    @param:JsonProperty("booking_date")
    val bookingDate: LocalDate?,
    @param:JsonProperty("value_date")
    val valueDate: LocalDate?,
    @param:JsonProperty("transaction_date")
    val transactionDate: LocalDate?,
    @param:JsonProperty("remittance_information")
    val remittanceInformation: List<String>
) {
    val counterpartyName = when (creditDebitIndicator) {
        CreditDebitIndicatorDto.DBIT -> creditor?.name
        CreditDebitIndicatorDto.CRDT -> debtor?.name
    }?.takeUnless { it.isBlank() }

    val counterpartyAccount = when (creditDebitIndicator) {
        CreditDebitIndicatorDto.DBIT -> creditorAccount?.other
        CreditDebitIndicatorDto.CRDT -> debtorAccount?.other
    }

    // ingen universell unikhet
    val fingerprint = createFingerprint(
        entryReference = entryReference,
        transactionAmount = transactionAmount,
        counterpartyName = counterpartyName,
        counterpartyAccount = counterpartyAccount,
        bookingDate = bookingDate,
        valueDate = valueDate,
        transactionDate = transactionDate
    )
}

private fun createFingerprint(
    entryReference: String?,
    transactionAmount: AmountDto,
    counterpartyName: String?,
    counterpartyAccount: AccountDto?,
    bookingDate: LocalDate?,
    valueDate: LocalDate?,
    transactionDate: LocalDate?
): String {
    if (entryReference != null && entryReference.length in 4..10) return fingerprintFromEntryReference(entryReference)
    // har sett tilfeller hvor entryReference "0" og "21" har blitt brukt på flere transaksjoner,
    // derfor er beste løsning å lage egen fingerprint basert på flere felter.
    // ignorerer også entry_reference som er 11 tegn eller mer, da jeg har observert at
    // enkelte kontoer har kontonumre som entry_reference (månedlig avdrag på boliglån fremkommer med samme entry_referenve hver måned)
    val payload = listOfNotNull(
        entryReference,
        bookingDate,
        valueDate,
        transactionDate,
        transactionAmount.amount,
        counterpartyName,
        counterpartyAccount?.identification
    ).joinToString("|")
    val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
    val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    return ("EBH:$b64")
}

private fun fingerprintFromEntryReference(entryReference: String): String {
    val candidate = "EB:$entryReference"
    if (candidate.length <= 36) return candidate
    val digest = MessageDigest.getInstance("SHA-256").digest(entryReference.toByteArray(Charsets.UTF_8))
    val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    return ("EB:$b64").take(36)
}

enum class StatusDto {
    BOOK, PDNG
}

enum class CreditDebitIndicatorDto {
    DBIT, CRDT
}

data class BankTransactionCodeDto(
    val code: String?
)

data class AmountDto(
    val currency: String,
    val amount: String,
) {
    val amountAsDouble = amount.toDoubleOrNull()
}

data class CreditorDto(
    val name: String
)

data class CreditorAccountDto(
    val iban: String?,
    val other: AccountDto
)

data class DebtorDto(
    val name: String
)

data class DebtorAccountDto(
    val iban: String?,
    val other: AccountDto
)

data class AccountDto(
    val identification: String,
    @param:JsonProperty("scheme_name")
    val schemeName: SchemeDto,
)

enum class SchemeDto {
    IBAN, BBAN, CPAN
}