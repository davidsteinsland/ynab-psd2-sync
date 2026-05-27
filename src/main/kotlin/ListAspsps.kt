package com.github.davidsteinsland.ynab_psd2_sync

import java.time.Duration

internal class ListAspsps(val client: EnableBankingClient): Command {
    override fun run() {
        client.aspsps("NO").path("aspsps").forEach {
            val name = it.path("name").asString()
            val country = it.path("country").asString()
            val authMethods = it.path("auth_methods").joinToString { m -> m.path("name").asString() }
            val maximumAuthValidity = Duration.ofSeconds(it.path("maximum_consent_validity").asLong())
            println("$name ($country) auth: $authMethods maximum validity: $maximumAuthValidity")
        }
    }
}