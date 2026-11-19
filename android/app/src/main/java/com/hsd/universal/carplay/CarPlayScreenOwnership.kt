package com.hsd.universal.carplay

/** Main-screen arbitration. Releasing the screen does not release the main-audio resource. */
internal class CarPlayScreenOwnership {
    var entity = CONTROLLER
        private set
    private var owner = CONTROLLER
    private var borrows = 0
    private var takeConstraint = ANYTIME
    private var borrowConstraint = ANYTIME
    private var unborrowConstraint = ANYTIME

    fun releaseController(): Boolean {
        if (entity != CONTROLLER) return false
        if (owner == CONTROLLER) setOwner(NONE) else { entity = owner; borrows = 0 }
        return true
    }

    fun requestController(): Boolean {
        if (entity == ACCESSORY && !allows(USER, if (borrows > 0) unborrowConstraint else takeConstraint)) return false
        setOwner(CONTROLLER)
        return true
    }

    /** Acknowledgement publishes the actual owner even when constraints deny a transfer. */
    fun accessory(resource: Map<*, *>): Boolean {
        if (number(resource, "resourceID") != SCREEN) return false
        val priority = number(resource, "transferPriority")
        when (number(resource, "transferType")) {
            TAKE -> {
                val take = number(resource, "takeConstraint")
                val borrow = number(resource, "borrowConstraint")
                if (priority !in priorities || take !in constraints || borrow !in constraints) return false
                if (entity != ACCESSORY && !allows(priority, if (borrows > 0) unborrowConstraint else takeConstraint)) return true
                setOwner(ACCESSORY)
                takeConstraint = take!!; borrowConstraint = borrow!!
            }
            UNTAKE -> {
                if (!releaseOnly(resource)) return false
                if (owner == ACCESSORY) setOwner(NONE)
            }
            BORROW -> {
                val unborrow = number(resource, "unborrowConstraint")
                if (priority !in priorities || unborrow !in constraints ||
                    resource.containsKey("takeConstraint") || resource.containsKey("borrowConstraint")) return false
                if (entity == ACCESSORY && borrows > 0) borrows++
                else if (entity != ACCESSORY && allows(priority, borrowConstraint)) {
                    entity = ACCESSORY; borrows = 1; unborrowConstraint = unborrow!!
                }
            }
            UNBORROW -> {
                if (!releaseOnly(resource)) return false
                if (entity == ACCESSORY && borrows > 0 && --borrows == 0) {
                    entity = owner; unborrowConstraint = ANYTIME
                }
            }
            else -> return false
        }
        return true
    }

    fun notification(): Map<String, Any> = mapOf("type" to "modesChanged", "params" to mapOf(
        "resources" to listOf(mapOf("resourceID" to SCREEN.toLong(), "entity" to entity.toLong(),
            "permanentEntity" to owner.toLong()))))

    private fun setOwner(value: Int) {
        entity = value; owner = value; borrows = 0
        takeConstraint = ANYTIME; borrowConstraint = ANYTIME; unborrowConstraint = ANYTIME
    }

    private fun releaseOnly(r: Map<*, *>) = listOf("transferPriority", "takeConstraint", "borrowConstraint", "unborrowConstraint").none(r::containsKey)
    private fun number(r: Map<*, *>, key: String) = (r[key] as? Number)?.toInt()
    private fun allows(priority: Int?, constraint: Int) = constraint == ANYTIME || constraint == USER && priority == USER

    companion object {
        const val NONE = 0
        const val CONTROLLER = 1
        const val ACCESSORY = 2
        const val SCREEN = 1
        private const val TAKE = 1
        private const val UNTAKE = 2
        private const val BORROW = 3
        private const val UNBORROW = 4
        private const val ANYTIME = 100
        private const val USER = 500
        private val priorities = setOf(100, 500)
        private val constraints = setOf(100, 500, 1000)
    }
}
