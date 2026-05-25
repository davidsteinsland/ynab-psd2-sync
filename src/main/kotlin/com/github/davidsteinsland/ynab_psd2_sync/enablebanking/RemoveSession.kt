package com.github.davidsteinsland.ynab_psd2_sync.enablebanking

internal class RemoveSession(val stateStore: StateStore, val name: String): Command {
    override fun run() {
        val root = stateStore.loadRoot()
        val sessions = root.sessions.filterNot { it.aspspName == name }
        stateStore.saveRoot(root.copy(sessions = sessions))
        log.info("Fjernet {}. Gjenværende: {}", name, sessions.size)
    }
}