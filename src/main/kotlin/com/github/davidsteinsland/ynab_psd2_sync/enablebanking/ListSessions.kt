package com.github.davidsteinsland.ynab_psd2_sync.enablebanking

import java.time.Instant

internal class ListSessions(val client: EnableBankingClient, val stateStore: StateStore): Command {
    override fun run() {
        val sessions = stateStore.load()
        if (sessions.isEmpty()) println("Ingen lagrede sesjoner.")
        else sessions.forEach {
            println("${it.aspspName} (${it.aspspCountry}) – id=${it.sessionId} gyldig til ${it.validUntil}")
            val session = client.getSession(it.sessionId)
            println("> Status: ${session.path("status").asString()}")
            println("> Valid until: ${Instant.parse(session.path("access").path("valid_until").asString())}")
        }
    }
}