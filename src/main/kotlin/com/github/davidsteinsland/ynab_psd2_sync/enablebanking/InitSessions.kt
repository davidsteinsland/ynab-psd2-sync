package com.github.davidsteinsland.ynab_psd2_sync.enablebanking

import tools.jackson.databind.JsonNode
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

internal class InitSessions(val client: EnableBankingClient, val stateStore: StateStore): Command {
    override fun run() {
        val app = client.application()
        val redirectUrl = app.path("redirect_urls").firstOrNull()?.asString()
            ?: error("Appen din har ingen redirect_urls konfigurert i kontrollpanelet")

        val aspspName = System.getenv("ENABLEBANKING_ASPSP_NAME") ?: promptAspspName(client)
        val aspspCountry = System.getenv("ENABLEBANKING_ASPSP_COUNTRY") ?: "NO"

        val state = UUID.randomUUID().toString()
        val validUntil = Instant.now().plus(180, ChronoUnit.DAYS)

        log.info("Starter auth mot {} ({}) – samtykke gyldig til {}", aspspName, aspspCountry, validUntil)
        val auth = client.startAuth(
            aspspName = aspspName,
            aspspCountry = aspspCountry,
            validUntil = validUntil,
            redirectUrl = redirectUrl,
            state = state,
        )

        val authUrl = auth.path("url").asString()
        println()
        println("============================================================")
        println(" 1) Åpne denne URL-en i nettleser og fullfør BankID-innlogging:")
        println()
        println("    $authUrl")
        println()
        println(" 2) Lim inn URL-en du blir redirected til etterpå:")
        println("============================================================")
        print("> ")
        val redirected = readln().trim()
        val code = URI.create(redirected).query
            .split("&")
            .map { it.split("=", limit = 2) }
            .first { it[0] == "code" }[1]

        val session = client.createSession(code)
        val sessionId = session.path("session_id").asString()
        val accountNodes = session.path("accounts")
        log.info("Sesjon {} opprettet med {} konto(er)", sessionId, accountNodes.size())

        val cachedAccounts = accountNodes.values().map { account ->
            val primaryAccountNumber = checkNotNull(mapAccountNumber(account.path("account_id").path("other"))) {
                "må tolke primary account_id: ${account.path("account_id").path("other").path("scheme").asString()}"
            }
            val accountNumbers = account.path("all_account_ids").mapNotNull { id ->
                mapAccountNumber(id)
            }

            CachedAccount(
                uid = account.path("uid").asString(),
                primaryAccountNumber = primaryAccountNumber,
                accountNumbers = accountNumbers,
                name = account.path("name").stringValue(),
                product = account.path("product").stringValue(),
                cashAccountType = account.path("cash_account_type").stringValue(),
                details = account,
            ).also {
                log.info("  {} -> {} ({}) [{}]", it.uid, it.name, it.primaryAccountNumber.accountNumber, it.cashAccountType ?: "?")
            }
        }

        val root = stateStore.loadRoot()
        val sessions = root.sessions.filterNot { it.aspspName == aspspName && it.aspspCountry == aspspCountry } +
                SessionState(
                    sessionId = sessionId,
                    aspspName = aspspName,
                    aspspCountry = aspspCountry,
                    validUntil = validUntil,
                    accounts = cachedAccounts,
                )
        stateStore.saveRoot(root.copy(sessions = sessions))
        log.info("Lagret {} sesjon(er) totalt", sessions.size)
    }
}

private fun promptAspspName(client: EnableBankingClient): String {
    val aspsps = client.aspsps("NO").path("aspsps").toList().sortedBy { it.path("name").asString() }
    println("Norske banker tilgjengelig:")
    aspsps.forEachIndexed { idx, it -> println("  [$idx] ${it.path("name").asString()}") }
    print("Velg nummer: ")
    return aspsps[readln().trim().toInt()].path("name").asString()
}

internal fun mapAccountNumber(node: JsonNode): AccountNumber? {
    val accountNumber = node.path("identification").asString()
    return when (val scheme = node.path("scheme_name").asString().lowercase()) {
        "bban" -> AccountNumber.BasicBankAccountNumber(accountNumber)
        "cpan" -> AccountNumber.CardPAN(accountNumber)
        "iban" -> AccountNumber.InternationalBankAccountNumber(accountNumber)
        else -> null.also {
            log.info("Ukjent account identification scheme: {}", scheme)
        }
    }
}