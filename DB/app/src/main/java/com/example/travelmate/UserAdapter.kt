package com.example.travelmate

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.travelmate.data.User
import com.example.travelmate.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UserAdapter(
    private val users: List<User>,
    private val repo: UserRepository
) : RecyclerView.Adapter<UserAdapter.UserViewHolder>() {

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvUserEmail: TextView = itemView.findViewById(R.id.tvUserEmail)
        val tvUserRole: TextView = itemView.findViewById(R.id.tvUserRole)
        val btnBlock: Button = itemView.findViewById(R.id.btnBlock)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_user_row, parent, false)
        return UserViewHolder(view)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        val user = users[position]
        holder.tvUserEmail.text = user.email
        holder.tvUserRole.text = "Role: ${user.role}"

        if (user.isBlocked) {
            holder.btnBlock.text = "Unblock"
            holder.btnBlock.setBackgroundColor(Color.parseColor("#4CAF50"))
        } else {
            holder.btnBlock.text = "Block"
            holder.btnBlock.setBackgroundColor(Color.parseColor("#F44336"))
        }

        holder.btnBlock.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                repo.updateUserBlockStatus(user.email, !user.isBlocked)
            }
            user.isBlocked = !user.isBlocked
            notifyItemChanged(position)
        }
    }

    override fun getItemCount() = users.size
}
