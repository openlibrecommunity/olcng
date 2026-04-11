package xyz.zarazaex.olc.ui

import android.graphics.Color
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.R
import xyz.zarazaex.olc.contracts.BaseAdapterListener
import xyz.zarazaex.olc.databinding.ItemRecyclerSubSettingBinding
import xyz.zarazaex.olc.dto.SubscriptionUpdateStatus
import xyz.zarazaex.olc.helper.ItemTouchHelperAdapter
import xyz.zarazaex.olc.helper.ItemTouchHelperViewHolder
import xyz.zarazaex.olc.util.Utils
import xyz.zarazaex.olc.viewmodel.SubscriptionsViewModel

class SubSettingRecyclerAdapter(
    private val viewModel: SubscriptionsViewModel,
    private val adapterListener: BaseAdapterListener?
) : RecyclerView.Adapter<SubSettingRecyclerAdapter.MainViewHolder>(), ItemTouchHelperAdapter {

    private var isUpdating = false

    fun setUpdating(updating: Boolean) {
        if (isUpdating != updating) {
            isUpdating = updating
            notifyDataSetChanged()
        }
    }

    override fun getItemCount() = viewModel.getAll().size

    override fun onBindViewHolder(holder: MainViewHolder, position: Int) {
        val subscriptions = viewModel.getAll()
        val subId = subscriptions[position].guid
        val subItem = subscriptions[position].subscription
        holder.itemSubSettingBinding.tvName.text = subItem.remarks
        holder.itemSubSettingBinding.tvUrl.text = subItem.url
        holder.itemSubSettingBinding.chkEnable.isChecked = subItem.enabled
        holder.itemSubSettingBinding.tvLastUpdated.text = Utils.formatTimestamp(subItem.lastUpdated)
        holder.itemView.setBackgroundColor(Color.TRANSPARENT)

        val subStatus = viewModel.getSubscriptionStatus(subId)
        when (subStatus?.status) {
            SubscriptionUpdateStatus.LOADING -> {
                holder.itemSubSettingBinding.progressBar.visibility = View.VISIBLE
                holder.itemSubSettingBinding.tvUpdateStatus.visibility = View.VISIBLE
                holder.itemSubSettingBinding.tvUpdateStatus.text = holder.itemView.context.getString(R.string.title_updating)
                holder.itemSubSettingBinding.tvUpdateStatus.setTextColor(Color.GRAY)
            }
            SubscriptionUpdateStatus.SUCCESS -> {
                holder.itemSubSettingBinding.progressBar.visibility = View.GONE
                holder.itemSubSettingBinding.tvUpdateStatus.visibility = View.VISIBLE
                holder.itemSubSettingBinding.tvUpdateStatus.text = "✓ ${subStatus.configCount}"
                holder.itemSubSettingBinding.tvUpdateStatus.setTextColor(Color.parseColor("#4CAF50"))
            }
            SubscriptionUpdateStatus.FAILED -> {
                holder.itemSubSettingBinding.progressBar.visibility = View.GONE
                holder.itemSubSettingBinding.tvUpdateStatus.visibility = View.VISIBLE
                holder.itemSubSettingBinding.tvUpdateStatus.text = "✗"
                holder.itemSubSettingBinding.tvUpdateStatus.setTextColor(Color.parseColor("#F44336"))
            }
            SubscriptionUpdateStatus.SKIPPED -> {
                holder.itemSubSettingBinding.progressBar.visibility = View.GONE
                holder.itemSubSettingBinding.tvUpdateStatus.visibility = View.VISIBLE
                holder.itemSubSettingBinding.tvUpdateStatus.text = "—"
                holder.itemSubSettingBinding.tvUpdateStatus.setTextColor(Color.GRAY)
            }
            else -> {
                holder.itemSubSettingBinding.progressBar.visibility = View.GONE
                holder.itemSubSettingBinding.tvUpdateStatus.visibility = View.GONE
            }
        }

        val isEnabled = !isUpdating

        holder.itemSubSettingBinding.layoutEdit.isClickable = isEnabled
        holder.itemSubSettingBinding.layoutEdit.alpha = if (isEnabled) 1.0f else 0.5f
        holder.itemSubSettingBinding.layoutEdit.setOnClickListener {
            if (isEnabled) {
                adapterListener?.onEdit(subId, position)
            }
        }

        holder.itemSubSettingBinding.layoutRemove.isClickable = isEnabled
        holder.itemSubSettingBinding.layoutRemove.alpha = if (isEnabled) 1.0f else 0.5f
        holder.itemSubSettingBinding.layoutRemove.setOnClickListener {
            if (isEnabled) {
                adapterListener?.onRemove(subId, position)
            }
        }

        holder.itemSubSettingBinding.chkEnable.isEnabled = isEnabled
        holder.itemSubSettingBinding.chkEnable.alpha = if (isEnabled) 1.0f else 0.5f
        holder.itemSubSettingBinding.chkEnable.setOnCheckedChangeListener { it, isChecked ->
            if (!it.isPressed || !isEnabled) return@setOnCheckedChangeListener
            subItem.enabled = isChecked
            viewModel.update(subId, subItem)
        }

        if (TextUtils.isEmpty(subItem.url)) {
            holder.itemSubSettingBinding.layoutUrl.visibility = View.GONE
            holder.itemSubSettingBinding.layoutShare.visibility = View.INVISIBLE
            holder.itemSubSettingBinding.chkEnable.visibility = View.INVISIBLE
            holder.itemSubSettingBinding.layoutLastUpdated.visibility = View.INVISIBLE
        } else {
            holder.itemSubSettingBinding.layoutUrl.visibility = View.VISIBLE
            holder.itemSubSettingBinding.layoutShare.visibility = View.VISIBLE
            holder.itemSubSettingBinding.chkEnable.visibility = View.VISIBLE
            holder.itemSubSettingBinding.layoutLastUpdated.visibility = View.VISIBLE
            holder.itemSubSettingBinding.layoutShare.isClickable = isEnabled
            holder.itemSubSettingBinding.layoutShare.alpha = if (isEnabled) 1.0f else 0.5f
            holder.itemSubSettingBinding.layoutShare.setOnClickListener {
                if (isEnabled) {
                    adapterListener?.onShare(subItem.url)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MainViewHolder {
        return MainViewHolder(
            ItemRecyclerSubSettingBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    class MainViewHolder(val itemSubSettingBinding: ItemRecyclerSubSettingBinding) :
        BaseViewHolder(itemSubSettingBinding.root), ItemTouchHelperViewHolder

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun onItemSelected() {
            itemView.setBackgroundColor(Color.LTGRAY)
        }

        fun onItemClear() {
            itemView.setBackgroundColor(0)
        }
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        viewModel.swap(fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onItemMoveCompleted() {
        adapterListener?.onRefreshData()
    }

    override fun onItemDismiss(position: Int) {
    }
}
