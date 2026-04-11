package xyz.zarazaex.olc.dto

enum class SubscriptionUpdateStatus {
    IDLE,
    LOADING,
    SUCCESS,
    FAILED,
    SKIPPED
}

data class SubscriptionStatus(
    val guid: String,
    val status: SubscriptionUpdateStatus = SubscriptionUpdateStatus.IDLE,
    val configCount: Int = 0
)
