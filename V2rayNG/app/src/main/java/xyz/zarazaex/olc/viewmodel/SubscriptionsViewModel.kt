package xyz.zarazaex.olc.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import xyz.zarazaex.olc.dto.SubscriptionCache
import xyz.zarazaex.olc.dto.SubscriptionItem
import xyz.zarazaex.olc.dto.SubscriptionStatus
import xyz.zarazaex.olc.dto.SubscriptionUpdateStatus
import xyz.zarazaex.olc.handler.MmkvManager
import xyz.zarazaex.olc.handler.SettingsChangeManager
import xyz.zarazaex.olc.handler.SettingsManager

class SubscriptionsViewModel : ViewModel() {
    private val subscriptions: MutableList<SubscriptionCache> =
        MmkvManager.decodeSubscriptions().toMutableList()

    val isUpdating = MutableLiveData<Boolean>(false)
    val subscriptionStatuses = MutableLiveData<Map<String, SubscriptionStatus>>(emptyMap())

    fun getAll(): List<SubscriptionCache> = subscriptions.toList()

    fun reload() {
        subscriptions.clear()
        subscriptions.addAll(MmkvManager.decodeSubscriptions())
        subscriptionStatuses.value = emptyMap()
    }

    fun updateSubscriptionStatus(guid: String, status: SubscriptionUpdateStatus, configCount: Int = 0) {
        val currentStatuses = subscriptionStatuses.value?.toMutableMap() ?: mutableMapOf()
        currentStatuses[guid] = SubscriptionStatus(guid, status, configCount)
        subscriptionStatuses.postValue(currentStatuses)
    }

    fun getSubscriptionStatus(guid: String): SubscriptionStatus? {
        return subscriptionStatuses.value?.get(guid)
    }

    fun remove(subId: String): Boolean {
        val changed = subscriptions.removeAll { it.guid == subId }
        if (changed) {
            SettingsManager.removeSubscriptionWithDefault(subId)
            SettingsChangeManager.makeSetupGroupTab()
        }
        return changed
    }

    fun update(subId: String, item: SubscriptionItem) {
        val idx = subscriptions.indexOfFirst { it.guid == subId }
        if (idx >= 0) {
            subscriptions[idx] = SubscriptionCache(subId, item)
            MmkvManager.encodeSubscription(subId, item)
        }
    }

    fun swap(fromPosition: Int, toPosition: Int) {
        if (fromPosition in subscriptions.indices && toPosition in subscriptions.indices) {
            val item = subscriptions.removeAt(fromPosition)
            subscriptions.add(toPosition, item)
            SettingsManager.swapSubscriptions(fromPosition, toPosition)
            SettingsChangeManager.makeSetupGroupTab()
        }
    }
}

