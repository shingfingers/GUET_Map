package com.example.guet_map.ui.discover

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.example.guet_map.R
import com.example.guet_map.databinding.ItemCheckinPostBinding
import com.example.guet_map.ui.discover.model.CheckInPost
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class CheckInPostAdapter(
    private val onLikeClick: (String) -> Unit,
    private val onCommentClick: (String) -> Unit
) : ListAdapter<CheckInPost, CheckInPostAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCheckinPostBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemCheckinPostBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(post: CheckInPost) {
            binding.apply {
                tvUserName.text = post.userName
                tvLocation.text = "📍 ${post.locationName}"
                tvContent.text = post.content
                tvTime.text = formatTime(post.timestamp)
                tvLikeCount.text = post.likeCount.toString()
                tvCommentCount.text = post.commentCount.toString()

                if (post.isLiked) {
                    ivLike.setColorFilter(Color.parseColor("#FF4081"))
                    tvLikeCount.setTextColor(Color.parseColor("#FF4081"))
                } else {
                    ivLike.setColorFilter(Color.parseColor("#666666"))
                    tvLikeCount.setTextColor(Color.parseColor("#666666"))
                }

                val topicsText = post.topics.joinToString(" ") { "#$it" }
                tvTopics.text = topicsText

                // 头像：优先使用真实头像 URL，否则显示昵称首字母
                val avatarUrl = post.userAvatar
                if (!avatarUrl.isNullOrEmpty()) {
                    ivAvatar.load(avatarUrl) {
                        crossfade(true)
                        placeholder(R.drawable.ic_avatar)
                        error(R.drawable.ic_avatar)
                        transformations(CircleCropTransformation())
                    }
                    tvAvatar.text = ""
                    tvAvatar.visibility = android.view.View.INVISIBLE
                } else {
                    ivAvatar.setImageDrawable(null)
                    tvAvatar.text = post.userName.firstOrNull()?.uppercase() ?: "?"
                    tvAvatar.visibility = android.view.View.VISIBLE
                }

                root.setOnClickListener { onCommentClick(post.id) }
                layoutLike.setOnClickListener { onLikeClick(post.id) }
                layoutComment.setOnClickListener { onCommentClick(post.id) }
            }
        }

        private fun formatTime(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp

            return when {
                diff < TimeUnit.MINUTES.toMillis(1) -> "刚刚"
                diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}分钟前"
                diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}小时前"
                else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<CheckInPost>() {
        override fun areItemsTheSame(oldItem: CheckInPost, newItem: CheckInPost): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: CheckInPost, newItem: CheckInPost): Boolean {
            return oldItem == newItem
        }
    }
}
