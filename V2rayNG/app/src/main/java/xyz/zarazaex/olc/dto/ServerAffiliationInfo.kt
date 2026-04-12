package xyz.zarazaex.olc.dto

data class ServerAffiliationInfo(var testDelayMillis: Long = 0L) {
    fun getTestDelayString(): String {
        return when {
            testDelayMillis == 0L -> ""
            testDelayMillis < 0L -> "Error"
            else -> "${testDelayMillis}ms"
        }
    }

    fun isReachable(): Boolean = testDelayMillis > 0L
}
