package com.github.davidsteinsland.ynab_psd2_sync.enablebanking

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.slf4j.LoggerFactory
import tools.jackson.core.util.DefaultIndenter
import tools.jackson.core.util.DefaultPrettyPrinter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.readValue
import java.io.File
import kotlin.system.exitProcess
import java.time.Instant

internal val log = LoggerFactory.getLogger("enablebanking.Main")

/**
 * Miljøvariabler:
 *   ENABLEBANKING_APPLICATION_ID - applikasjons-ID (UUID) fra https://enablebanking.com/cp/applications
 *   ENABLEBANKING_PRIVATE_KEY    - PEM-innhold (selve nøkkelen, ikke en filsti) lastet ned ved opprettelse av appen
 *   YNAB_ACCESS_TOKEN            - Personal Access Token fra app.ynab.com (kun nødvendig for --map-accounts og --sync-ynab)
 *
 * Hemmeligheter støtter også `_FILE`-suffix (Docker-konvensjon), som leser verdien fra fila:
 *   ENABLEBANKING_PRIVATE_KEY_FILE=/run/secrets/eb-private-key.pem
 *   YNAB_ACCESS_TOKEN_FILE=/run/secrets/ynab-token
 *
 * Bruk:
 *   ./gradlew :enablebanking:run --args="--list-aspsps"
 *   ./gradlew :enablebanking:run --args="--list-sessions"
 *   ./gradlew :enablebanking:run --args="--init"
 *   ./gradlew :enablebanking:run --args="--map-accounts"      # mapper EB-kontoer til YNAB-kontoer
 *   ./gradlew :enablebanking:run                              # henter siste 7 dager
 *   ./gradlew :enablebanking:run --args="--sync-ynab"         # syncer til YNAB
 */
fun main(args: Array<String>) {
    try {
        run(args.toList())
    } catch (e: Exception) {
        log.error(e.message, e)
        System.err.println(e.message)
        exitProcess(1)
    }
}

internal sealed interface Command {
    fun run()
}

private fun run(args: List<String>) {
    val prettyPrinter = DefaultPrettyPrinter().apply {
        indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE)
    }

    val objectMapper = jacksonMapperBuilder()
        .defaultPrettyPrinter(prettyPrinter)
        .build()

    val applicationId = env("ENABLEBANKING_APPLICATION_ID")
    val client = EnableBankingClient(applicationId, secret("ENABLEBANKING_PRIVATE_KEY"), objectMapper)

    val stateFile = parseFlag(args, "--state")?.let { File(it) } ?: File(".state.json")
    val stateStore = StateStore(stateFile, objectMapper)

    val mappingsFile = parseFlag(args, "--mappings")?.let { File(it) } ?: File(".ynab.json")
    val mappingsStore = YnabMappingsStore(mappingsFile, objectMapper)

    val ynabClient by lazy { YnabClient(secret("YNAB_ACCESS_TOKEN"), objectMapper) }

    val expiryNotifier = SessionExpiryNotifier(
        ntfyTopic = System.getenv("NTFY_TOPIC").orEmpty(),
        warningDays = System.getenv("SESSION_EXPIRY_WARNING_DAYS")?.toLongOrNull() ?: 14L,
    )

    val cmd = when {
        "--list-aspsps" in args -> ListAspsps(client)
        "--list-sessions" in args -> ListSessions(client, stateStore)
        "--remove-session" in args -> {
            val name = args.getOrNull(args.indexOf("--remove-session") + 1)
                ?: error("Bruk: --remove-session \"<aspsp name>\"")
            RemoveSession(stateStore, name)
        }
        "--init" in args -> InitSessions(client, stateStore)
        "--map-accounts" in args -> MapAccounts(ynabClient, mappingsStore, objectMapper)
        "--sync-ynab" in args -> SyncToYnab(ynabClient, mappingsStore, objectMapper)
        else -> FetchTransactions(client, stateStore, mappingsStore, objectMapper, expiryNotifier)
    }
    cmd.run()
}

private fun parseFlag(args: List<String>, name: String): String? {
    val idx = args.indexOf(name)
    if (idx < 0) return null
    return args.getOrNull(idx + 1) ?: error("Bruk: $name <verdi>")
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = AccountNumber.BasicBankAccountNumber::class, name = "BBAN"),
    JsonSubTypes.Type(value = AccountNumber.CardPAN::class, name = "CPAN"),
    JsonSubTypes.Type(value = AccountNumber.InternationalBankAccountNumber::class, name = "IBAN"),
)
sealed interface AccountNumber {
    val accountNumber: String

    data class BasicBankAccountNumber(
        @param:JsonProperty("value") override val accountNumber: String,
    ) : AccountNumber

    data class CardPAN(
        @param:JsonProperty("value") override val accountNumber: String,
    ) : AccountNumber

    data class InternationalBankAccountNumber(
        @param:JsonProperty("value") override val accountNumber: String,
    ) : AccountNumber
}

private fun env(name: String) = System.getenv(name)
    ?: error("Miljøvariabel $name må være satt")

/**
 * Leser en hemmelighet. Hvis `<NAME>_FILE` er satt, leses verdien fra fila
 * (newlines i slutten trimmes). Ellers brukes `<NAME>` direkte. Dette følger
 * Docker `_FILE`-konvensjonen og lar oss montere PEM-nøkler og tokens som filer
 * i stedet for å legge dem i env-variabler.
 */
private fun secret(name: String): String {
    System.getenv("${name}_FILE")?.let { path ->
        val file = File(path)
        check(file.isFile) { "Filsti i ${name}_FILE finnes ikke: $path" }
        return file.readText().trimEnd('\n', '\r')
    }
    return env(name)
}

internal data class RootState(
    val sessions: List<SessionState> = emptyList(),
)

internal data class SessionState(
    val sessionId: String,
    val aspspName: String,
    val aspspCountry: String,
    val validUntil: Instant,
    val accounts: List<CachedAccount> = emptyList(),
)

internal data class CachedAccount(
    val uid: String,
    val primaryAccountNumber: AccountNumber,
    val accountNumbers: List<AccountNumber>,
    val name: String,
    val product: String,
    val cashAccountType: String? = null,
    val details: JsonNode? = null,
) {
    init {
        check(accountNumbers.isNotEmpty()) {
            "kan ikke ha en tom liste av account numbers"
        }
    }
}

internal class StateStore(private val file: File, private val objectMapper: ObjectMapper) {
    fun saveRoot(root: RootState) {
        file.writeText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root))
        log.info("Lagret state ({} sesjon(er)) i {}", root.sessions.size, file)
    }

    /**
     * Leser state-fila. Støtter både ny `RootState`-form (objekt med `sessions`) og
     * gammel form (rå JSON-array av `SessionState`) for å migrere uten å miste sesjoner.
     */
    fun loadRoot(): RootState {
        if (!file.exists()) return RootState()
        return objectMapper.readValue<RootState>(file)
    }

    fun load(): List<SessionState> = loadRoot().sessions
}
