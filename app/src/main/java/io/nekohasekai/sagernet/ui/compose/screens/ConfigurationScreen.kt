package io.nekohasekai.sagernet.ui.compose.screens

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.toShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.group.BuiltinPool
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.parseShareLinks
import io.nekohasekai.sagernet.ui.compose.ComposeProfileSettingsActivity
import io.nekohasekai.sagernet.ui.compose.components.EmptyState
import io.nekohasekai.sagernet.ui.compose.components.OwenclaveTopAppBar
import io.nekohasekai.sagernet.ui.compose.components.ProfileCard
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.nekohasekai.sagernet.bg.test.BatchProbeCoordinator
import io.nekohasekai.sagernet.ui.compose.components.BuiltinPoolStatus
import io.nekohasekai.sagernet.ui.compose.components.InlineProbeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun ConfigurationScreen(
    onMenuClick: () -> Unit,
    serviceRunning: Boolean = false,
    serviceConnected: Boolean = false,
    batchTestProgress: Pair<Int, Int>? = null,
    onBatchTestProgress: (Pair<Int, Int>?) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val viewModel: io.nekohasekai.sagernet.ui.compose.ConfigurationViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val anonymizeNames by viewModel.anonymizeNames.collectAsStateWithLifecycle()
    val selectedProfileId by viewModel.selectedProfileId.collectAsStateWithLifecycle()
    val selectedGroup by viewModel.currentGroup.collectAsStateWithLifecycle()
    val selectedGroupId = selectedGroup?.id
    val isBuiltinGroup = remember(selectedGroupId) {
        selectedGroupId?.let(BuiltinPool::isPool) == true
    }
    val selectedGroupName = selectedGroup?.displayName().orEmpty()
    var showProtocolPicker by remember { mutableStateOf(false) }
    val pingingIds by BatchProbeCoordinator.testingIds.collectAsStateWithLifecycle()
    val progress by BatchProbeCoordinator.progress.collectAsStateWithLifecycle()
    val reloadAccess = remember { kotlinx.coroutines.sync.Mutex() }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val startup by io.nekohasekai.sagernet.group.OwengStartup.state.collectAsStateWithLifecycle()

    LaunchedEffect(startup.running) {
        if (!startup.running) {
            viewModel.loadProfiles()
        }
    }

    LaunchedEffect(progress) {
        onBatchTestProgress(progress?.let { it.completed to it.total })
    }

    DisposableEffect(lifecycleOwner, selectedGroupId) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.loadProfiles()
            }
        }
        val listener = object : GroupManager.Listener {
            override suspend fun groupAdd(group: io.nekohasekai.sagernet.database.ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit
            override suspend fun groupUpdated(group: io.nekohasekai.sagernet.database.ProxyGroup) = groupUpdated(group.id)
            override suspend fun groupUpdated(groupId: Long) {
                if (groupId == selectedGroupId) viewModel.loadProfiles()
            }
        }
        GroupManager.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            GroupManager.removeListener(listener)
        }
    }

    fun importFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        scope.launch(Dispatchers.IO) {
            try {
                if (text.startsWith("owenkey://", ignoreCase = true)) {
                    val import = io.nekohasekai.sagernet.ktx.parseOwenkeyLink(text.trim())
                    if (import != null) {
                        io.nekohasekai.sagernet.database.GroupManager.createGroup(import.group)
                        import.profiles.forEach { profile ->
                            profile.id = 0
                            profile.groupId = import.group.id
                            profile.userOrder = SagerDatabase.proxyDao.nextOrder(import.group.id) ?: 1
                            profile.id = SagerDatabase.proxyDao.addProxy(profile)
                        }
                        if (import.group.type == GroupType.SUBSCRIPTION && import.group.subscription?.link?.isNotEmpty() == true) {
                            val created = SagerDatabase.groupDao.getById(import.group.id)
                            if (created != null) {
                                io.nekohasekai.sagernet.group.GroupUpdater.executeUpdate(created, true)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Group imported with ${import.profiles.size} profiles", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    val beans = parseShareLinks(text)
                    if (beans.isNotEmpty()) {
                        val groupId = DataStore.selectedGroupForImport()
                        var lastId = 0L
                        beans.forEach { bean ->
                            val p = ProfileManager.createProfile(groupId, bean)
                            lastId = p.id
                        }
                        if (lastId > 0) DataStore.selectedProxy = lastId
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Imported ${beans.size} profile(s)", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "No valid share links found", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, e.message ?: "Import failed", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    if (showProtocolPicker) {
        ProtocolPickerDialog(
            onSelect = { type ->
                showProtocolPicker = false
                context.startActivity(
                    Intent(context, ComposeProfileSettingsActivity::class.java).apply {
                        putExtra(ComposeProfileSettingsActivity.EXTRA_PROFILE_TYPE, type)
                    }
                )
            },
            onDismiss = { showProtocolPicker = false }
        )
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            OwenclaveTopAppBar(
                title = "Configuration",
                scrollBehavior = scrollBehavior,
                actions = {
                    // Only offer "test all" when there is at least one profile to test.
                    if (profiles.isNotEmpty() || progress != null) {
                        if (progress != null) {
                            IconButton(
                                onClick = { BatchProbeCoordinator.cancel() },
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Cancel test")
                            }
                        } else {
                            IconButton(
                                onClick = { selectedGroupId?.let { BatchProbeCoordinator.start(it) } },
                            ) {
                                Icon(Icons.Filled.Speed, contentDescription = "Test all")
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    IconButton(
                        onClick = { importFromClipboard() },
                    ) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "Import subscription from clipboard")
                    }
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        onClick = { showProtocolPicker = true },
                        shape = MaterialShapes.Cookie9Sided.toShape(),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Add, contentDescription = "Add profile", modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when {
                    // Keep the LazyColumn always composed (even when empty) so that
                    // adding/removing the only profile animates the card in/out via
                    // animateItem() instead of hard-swapping the whole screen.
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            top = 8.dp,
                            bottom = io.nekohasekai.sagernet.ui.compose.components.StatsBarBottomInset,
                        ),
                    ) {
                        item(key = "__pool_status__") {
                            val activeProgress = progress
                            if (isBuiltinGroup) BuiltinPoolStatus(activeProgress) else if (activeProgress != null) {
                                InlineProbeStatus(activeProgress, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            } else {
                                Text(selectedGroupName, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.titleMedium)
                                androidx.compose.material3.TextButton(onClick = {
                                    scope.launch {
                                        val id = withContext(Dispatchers.IO) { BuiltinPool.initialize() }
                                        DataStore.selectedGroup = id
                                        viewModel.loadProfiles()
                                    }
                                }) { Text("Открыть общий список") }
                            }
                        }
                        if (profiles.isEmpty()) {
                            item(key = "__empty__") {
                                Box(
                                    modifier = Modifier
                                        .fillParentMaxSize()
                                        .animateItem(),
                                    contentAlignment = androidx.compose.ui.Alignment.Center,
                                ) {
                                    EmptyState(
                                        message = "No profiles. Tap + to add one.",
                                        icon = Icons.Filled.Add,
                                    )
                                }
                            }
                        }
                        items(profiles, key = { it.id }) { entity ->
                            ProfileCard(
                                entity = entity,
                                selected = entity.id == selectedProfileId,
                                connected = entity.id == selectedProfileId && serviceConnected,
                                connectionStart = if (entity.id == selectedProfileId && serviceConnected) DataStore.connectionStart else 0L,
                                pinging = entity.id in pingingIds,
                                anonymizeNames = anonymizeNames,
                                modifier = Modifier.animateItem(),
                                onClick = {
                                    val entity = entity
                                    io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher {
                                        val update = selectedProfileId != entity.id
                                        if (!update) return@runOnDefaultDispatcher
                                        viewModel.selectProfile(entity.id)
                                        if (serviceRunning && reloadAccess.tryLock()) {
                                            try {
                                                SagerNet.reloadService()
                                            } finally {
                                                reloadAccess.unlock()
                                            }
                                        }
                                    }
                                },
                                onEdit = {
                                    context.startActivity(
                                        Intent(context, ComposeProfileSettingsActivity::class.java).apply {
                                            putExtra(ComposeProfileSettingsActivity.EXTRA_PROFILE_ID, entity.id)
                                            putExtra(ComposeProfileSettingsActivity.EXTRA_PROFILE_TYPE, entity.type)
                                        }
                                    )
                                },
                                onShare = if (entity.hasShareLink()) {
                                    {
                                        scope.launch(Dispatchers.IO) {
                                            val link = entity.toLink()
                                            if (link != null) {
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, link)
                                                }
                                                withContext(Dispatchers.Main) {
                                                    context.startActivity(Intent.createChooser(shareIntent, "Share"))
                                                }
                                            }
                                        }
                                    }
                                } else null,
                                onDelete = {
                                    scope.launch(Dispatchers.IO) {
                                        val groupId = DataStore.currentGroupId()
                                        ProfileManager.deleteProfile(groupId, entity.id)
                                    }
                                },
                                onPing = {
                                    io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher {
                                        BatchProbeCoordinator.testOne(entity)
                                    }
                                },
                            )
                        }
                        item(key = "__sponsored__") {
                            Text(
                                text = "sponsored by openlibrecommunity",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
            }
    }
}

@Composable
private fun ProtocolPickerDialog(
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val protocols = listOf(
        "SOCKS" to ProxyEntity.TYPE_SOCKS,
        "HTTP" to ProxyEntity.TYPE_HTTP,
        "Shadowsocks" to ProxyEntity.TYPE_SS,
        "ShadowsocksR" to ProxyEntity.TYPE_SSR,
        "VMess" to ProxyEntity.TYPE_VMESS,
        "VLESS" to ProxyEntity.TYPE_VLESS,
        "Trojan" to ProxyEntity.TYPE_TROJAN,
        "Naive" to ProxyEntity.TYPE_NAIVE,
        "Hysteria 2" to ProxyEntity.TYPE_HYSTERIA2,
        "SSH" to ProxyEntity.TYPE_SSH,
        "WireGuard" to ProxyEntity.TYPE_WG,
        "Mieru" to ProxyEntity.TYPE_MIERU,
        "TUIC" to ProxyEntity.TYPE_TUIC5,
        "Juicity" to ProxyEntity.TYPE_JUICITY,
        "HTTP/3" to ProxyEntity.TYPE_HTTP3,
        "AnyTLS" to ProxyEntity.TYPE_ANYTLS,
        "ShadowQUIC" to ProxyEntity.TYPE_SHADOWQUIC,
        "TrustTunnel" to ProxyEntity.TYPE_TRUSTTUNNEL,
        "Snell" to ProxyEntity.TYPE_SNELL,
        "MASQUE" to ProxyEntity.TYPE_MASQUE,
        "Chain" to ProxyEntity.TYPE_CHAIN,
        "Balancer" to ProxyEntity.TYPE_BALANCER,
        "Custom Config" to ProxyEntity.TYPE_CONFIG,
    )

    io.nekohasekai.sagernet.ui.compose.components.ExpressiveDialog(onDismissRequest = onDismiss) {
        Text(
            text = "New profile",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = "Choose a protocol",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        LazyColumn(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(protocols.size) { index ->
                val (name, type) = protocols[index]
                // Uniform small radius: the outer LazyColumn clip (24dp) defines the
                // overall rounded group, so the first/last items no longer get their
                // corners harshly squared off by the scroll clip.
                androidx.compose.material3.Surface(
                    onClick = { onSelect(type) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        io.nekohasekai.sagernet.ui.compose.components.ShapedIconStatic(
                            icon = Icons.Filled.Add,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            size = 40.dp,
                            shape = io.nekohasekai.sagernet.ui.compose.components.shapeForSeed(name),
                        )
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
}
