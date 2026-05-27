package com.accucodeai.kash.api.binfmt

internal class DefaultBinfmtRegistry : BinfmtRegistry {
    private val byName: MutableMap<String, BinfmtHandler> = linkedMapOf()

    override fun handlers(): List<BinfmtHandler> = byName.values.sortedBy { it.priority }

    override fun register(handler: BinfmtHandler) {
        byName[handler.name] = handler
    }

    override fun unregister(name: String) {
        byName.remove(name)
    }
}
