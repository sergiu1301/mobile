package com.example.travelmate

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.travelmate.data.User
import com.example.travelmate.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UserAdapter(
    private val users: MutableList<User>,
    private val repo: UserRepository,
    private val isSuperAdmin: Boolean
) : RecyclerView.Adapter<UserAdapter.UserViewHolder>() {

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvUserEmail: TextView = itemView.findViewById(R.id.tvUserEmail)
        val tvUserRole: TextView = itemView.findViewById(R.id.tvUserRole)

        val switchRole: SwitchCompat = itemView.findViewById(R.id.switchRole)
        val switchBlock: SwitchCompat = itemView.findViewById(R.id.switchBlock)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_user_card, parent, false)
        return UserViewHolder(view)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        val user = users[position]

        holder.tvUserEmail.text = user.email
        holder.tvUserRole.text = "Role: ${user.role}"

        // -------------------------------
        // BLOCK SWITCH
        // -------------------------------
        holder.switchBlock.setOnCheckedChangeListener(null)
        holder.switchBlock.isChecked = user.isBlocked

        holder.switchBlock.setOnCheckedChangeListener { _, isChecked ->
            user.isBlocked = isChecked
            notifyItemChanged(position)

            CoroutineScope(Dispatchers.IO).launch {
                repo.updateUserBlockStatus(user.email, isChecked)
            }
        }

        // ---------------------------------------------------
        // ROLE SWITCH (Admin <-> User) — doar super-admin!!
        // ---------------------------------------------------
        holder.switchRole.setOnCheckedChangeListener(null)
        holder.switchRole.isChecked = user.role == "admin"

        if (!isSuperAdmin) {
            holder.switchRole.isEnabled = false
        } else {
            holder.switchRole.setOnCheckedChangeListener { _, isChecked ->
                val newRole = if (isChecked) "admin" else "user"
                user.role = newRole
                holder.tvUserRole.text = "Role: $newRole"

                CoroutineScope(Dispatchers.IO).launch {
                    repo.updateUserRole(user.email, newRole)
                }
            }
        }
    }

    override fun getItemCount() = users.size
}
