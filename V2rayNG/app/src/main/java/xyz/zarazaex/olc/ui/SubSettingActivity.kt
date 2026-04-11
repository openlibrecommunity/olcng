package xyz.zarazaex.olc.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.R
import xyz.zarazaex.olc.contracts.BaseAdapterListener
import xyz.zarazaex.olc.databinding.ActivitySubSettingBinding
import xyz.zarazaex.olc.databinding.ItemQrcodeBinding
import xyz.zarazaex.olc.extension.toast
import xyz.zarazaex.olc.handler.AngConfigManager
import xyz.zarazaex.olc.handler.MmkvManager
import xyz.zarazaex.olc.helper.SimpleItemTouchHelperCallback
import xyz.zarazaex.olc.util.QRCodeDecoder
import xyz.zarazaex.olc.util.Utils
import xyz.zarazaex.olc.viewmodel.SubscriptionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SubSettingActivity : BaseActivity() {
    private val binding by lazy { ActivitySubSettingBinding.inflate(layoutInflater) }
    private val ownerActivity: SubSettingActivity
        get() = this
    private val viewModel: SubscriptionsViewModel by viewModels()
    private lateinit var adapter: SubSettingRecyclerAdapter
    private var mItemTouchHelper: ItemTouchHelper? = null
    private val share_method: Array<out String> by lazy {
        resources.getStringArray(R.array.share_sub_method)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_sub_setting))

        adapter = SubSettingRecyclerAdapter(viewModel, ActivityAdapterListener())

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        mItemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter))
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)

        viewModel.isUpdating.observe(this) { isUpdating ->
            adapter.setUpdating(isUpdating)
        }

        viewModel.subscriptionStatuses.observe(this) { statuses ->
            adapter.notifyDataSetChanged()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_sub_setting, menu)
        viewModel.isUpdating.observe(this) { isUpdating ->
            menu.findItem(R.id.sub_update)?.isEnabled = !isUpdating
            menu.findItem(R.id.add_config)?.isEnabled = !isUpdating
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.add_config -> {
                startActivity(Intent(this, SubEditActivity::class.java))
                true
            }

            R.id.sub_update -> {
                if (viewModel.isUpdating.value == true) {
                    return true
                }

                showLoading()
                viewModel.isUpdating.value = true
                viewModel.subscriptionStatuses.value = emptyMap()

                lifecycleScope.launch(Dispatchers.Main) {
                    val subscriptions = viewModel.getAll()
                    var totalConfigCount = 0
                    var successCount = 0
                    var failureCount = 0
                    var skipCount = 0

                    val jobs = subscriptions.map { subscription ->
                        launch(Dispatchers.IO) {
                            val subId = subscription.guid
                            
                            launch(Dispatchers.Main) {
                                viewModel.updateSubscriptionStatus(subId, xyz.zarazaex.olc.dto.SubscriptionUpdateStatus.LOADING)
                            }

                            val result = AngConfigManager.updateConfigViaSub(subscription)
                            
                            launch(Dispatchers.Main) {
                                when {
                                    result.successCount > 0 -> {
                                        viewModel.updateSubscriptionStatus(
                                            subId, 
                                            xyz.zarazaex.olc.dto.SubscriptionUpdateStatus.SUCCESS,
                                            result.configCount
                                        )
                                    }
                                    result.skipCount > 0 -> {
                                        viewModel.updateSubscriptionStatus(
                                            subId, 
                                            xyz.zarazaex.olc.dto.SubscriptionUpdateStatus.SKIPPED
                                        )
                                    }
                                    else -> {
                                        viewModel.updateSubscriptionStatus(
                                            subId, 
                                            xyz.zarazaex.olc.dto.SubscriptionUpdateStatus.FAILED
                                        )
                                    }
                                }
                            }

                            synchronized(this@SubSettingActivity) {
                                totalConfigCount += result.configCount
                                successCount += result.successCount
                                failureCount += result.failureCount
                                skipCount += result.skipCount
                            }
                        }
                    }

                    jobs.forEach { it.join() }

                    delay(500L)
                    viewModel.isUpdating.value = false
                    
                    if (successCount + failureCount + skipCount == 0) {
                        toast(R.string.title_update_subscription_no_subscription)
                    } else if (successCount > 0 && failureCount + skipCount == 0) {
                        toast(getString(R.string.title_update_config_count, totalConfigCount))
                    } else {
                        toast(
                            getString(
                                R.string.title_update_subscription_result,
                                totalConfigCount, successCount, failureCount, skipCount
                            )
                        )
                    }
                    hideLoading()
                    refreshData()
                }

                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshData() {
        viewModel.reload()
        adapter.notifyDataSetChanged()
    }

    private inner class ActivityAdapterListener : BaseAdapterListener {
        override fun onEdit(guid: String, position: Int) {
            startActivity(
                Intent(ownerActivity, SubEditActivity::class.java)
                    .putExtra("subId", guid)
            )
        }

        override fun onRemove(guid: String, position: Int) {
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE)) {
                AlertDialog.Builder(ownerActivity)
                    .setMessage(R.string.del_config_comfirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        viewModel.remove(guid)
                        refreshData()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                viewModel.remove(guid)
                refreshData()
            }
        }

        override fun onShare(url: String) {
            AlertDialog.Builder(ownerActivity)
                .setItems(share_method.asList().toTypedArray()) { _, i ->
                    try {
                        when (i) {
                            0 -> {
                                val ivBinding =
                                    ItemQrcodeBinding.inflate(LayoutInflater.from(ownerActivity))
                                ivBinding.ivQcode.setImageBitmap(
                                    QRCodeDecoder.createQRCode(
                                        url

                                    )
                                )
                                AlertDialog.Builder(ownerActivity).setView(ivBinding.root).show()
                            }

                            1 -> {
                                Utils.setClipboard(ownerActivity, url)
                            }

                            else -> ownerActivity.toast("else")
                        }
                    } catch (e: Exception) {
                        Log.e(AppConfig.TAG, "Share subscription failed", e)
                    }
                }.show()
        }

        override fun onRefreshData() {
            refreshData()
        }
    }
}
