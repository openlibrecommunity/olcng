package io.nekohasekai.sagernet.ui.compose

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.group.BuiltinPool
import io.nekohasekai.sagernet.bg.test.pingComparator
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConfigurationViewModel(app: Application) : AndroidViewModel(app) {

    private val _profiles = MutableStateFlow<List<ProxyEntity>>(emptyList())
    val profiles: StateFlow<List<ProxyEntity>> = _profiles.asStateFlow()

    private val _selectedProfileId = MutableStateFlow(DataStore.selectedProxy)
    val selectedProfileId: StateFlow<Long> = _selectedProfileId.asStateFlow()

    private val _currentGroup = MutableStateFlow<ProxyGroup?>(null)
    val currentGroup: StateFlow<ProxyGroup?> = _currentGroup.asStateFlow()

    private val _anonymizeNames = MutableStateFlow(DataStore.anonymizeServerNames)
    val anonymizeNames: StateFlow<Boolean> = _anonymizeNames.asStateFlow()
    private val loadMutex = Mutex()
    private var observation: Job? = null
    private var observedGroupId: Long? = null

    init {
        loadProfiles()
    }

    fun loadProfiles() {
        viewModelScope.launch(Dispatchers.IO) {
            loadMutex.withLock {
                val groupId = DataStore.currentGroupId()
                val group = SagerDatabase.groupDao.getById(groupId)
                _selectedProfileId.value = DataStore.selectedProxy
                _anonymizeNames.value = DataStore.anonymizeServerNames
                if (observedGroupId == groupId && observation?.isActive == true) {
                    _currentGroup.value = group
                    return@withLock
                }
                observation?.cancelAndJoin()
                observedGroupId = groupId
                _currentGroup.value = group
                val isPool = BuiltinPool.isPool(groupId)
                observation = viewModelScope.launch(Dispatchers.IO) {
                    combine(
                        SagerDatabase.proxyDao.getByGroupFlow(groupId).conflate(),
                        _currentGroup,
                        _anonymizeNames,
                    ) { profiles, currentGroup, anonymize ->
                        withContext(Dispatchers.Default) {
                            when {
                                isPool || currentGroup?.order == GroupOrder.BY_DELAY ->
                                    profiles.sortedWith(pingComparator({ it.status }, { it.ping }, { it.userOrder }, { it.id }))
                                currentGroup?.order == GroupOrder.BY_NAME -> profiles.sortedBy { it.displayName(anonymize) }
                                else -> profiles
                            }
                        }
                    }.collect { _profiles.value = it }
                }
            }
        }
    }

    fun selectProfile(profileId: Long) {
        DataStore.selectedProxy = profileId
        _selectedProfileId.value = profileId
    }

    fun deleteProfile(profileId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val groupId = DataStore.currentGroupId()
            ProfileManager.deleteProfile(groupId, profileId)
            loadProfiles()
        }
    }
}

class GroupViewModel(app: Application) : AndroidViewModel(app) {

    private val _groups = MutableStateFlow<List<ProxyGroup>>(emptyList())
    val groups: StateFlow<List<ProxyGroup>> = _groups.asStateFlow()

    fun loadGroups() {
        viewModelScope.launch(Dispatchers.IO) {
            _groups.value = SagerDatabase.groupDao.allGroups()
        }
    }

    fun deleteGroup(groupId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            io.nekohasekai.sagernet.database.GroupManager.deleteGroup(groupId)
            loadGroups()
        }
    }
}

class RouteViewModel(app: Application) : AndroidViewModel(app) {

    private val _rules = MutableStateFlow<List<RuleEntity>>(emptyList())
    val rules: StateFlow<List<RuleEntity>> = _rules.asStateFlow()

    fun loadRules() {
        viewModelScope.launch(Dispatchers.IO) {
            _rules.value = SagerDatabase.rulesDao.allRules()
        }
    }

    fun deleteRule(ruleId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            SagerDatabase.rulesDao.deleteById(ruleId)
            loadRules()
        }
    }
}
