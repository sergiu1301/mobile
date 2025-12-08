package com.example.travelmate

import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.ImageButton
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.example.travelmate.data.Trip
import com.example.travelmate.ui.activities.TripDetailsActivity

class TripAdapter(
    private val trips: List<Trip>,
    private val onDeleteClick: ((Trip) -> Unit)? = null // 🆕 callback opțional pentru ștergere
) : RecyclerView.Adapter<TripAdapter.TripViewHolder>() {

    class TripViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardContainer: CardView = itemView.findViewById(R.id.cardContainer)
        val tvTitle: TextView = itemView.findViewById(R.id.tvTripTitle)
        val tvDestination: TextView = itemView.findViewById(R.id.tvTripDestination)
        val tvDates: TextView = itemView.findViewById(R.id.tvTripDates)
        val tvWeather: TextView = itemView.findViewById(R.id.tvTripWeather)
        val ivWeatherIcon: ImageView = itemView.findViewById(R.id.ivWeatherIcon)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteTrip) // 🆕 buton delete
        val tvPendingBadge: TextView = itemView.findViewById(R.id.tvPendingBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TripViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_trip, parent, false)
        return TripViewHolder(view)
    }

    override fun onBindViewHolder(holder: TripViewHolder, position: Int) {
        val trip = trips[position]

        holder.tvTitle.text = trip.title
        holder.tvDestination.text = "📍 ${trip.destination}"
        holder.tvDates.text = "🗓️ ${trip.startDate} → ${trip.endDate}"

        holder.tvPendingBadge.visibility = if (trip.pendingSync) View.VISIBLE else View.GONE
        holder.cardContainer.alpha = if (trip.pendingSync) 0.85f else 1f

        if (trip.weatherTemp != null && trip.weatherDescription != null) {
            holder.tvWeather.text = "${trip.weatherTemp}, ${trip.weatherDescription}"
            holder.ivWeatherIcon.setImageResource(getWeatherIcon(trip.weatherDescription))
            holder.tvWeather.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            holder.tvWeather.text = "Weather unavailable ☁️"
            holder.ivWeatherIcon.setImageResource(R.drawable.ic_weather_unknown)
            holder.tvWeather.setTextColor(Color.parseColor("#E65100"))
        }

        holder.cardContainer.setOnClickListener {
            val context = holder.itemView.context
            val intent = Intent(context, TripDetailsActivity::class.java)
            intent.putExtra("trip_id", trip.id)
            context.startActivity(intent)
        }

        holder.btnDelete.setOnClickListener {
            onDeleteClick?.invoke(trip)
        }
    }

    override fun getItemCount(): Int = trips.size

    private fun getWeatherIcon(description: String?): Int {
        val lower = description?.lowercase() ?: ""
        return when {
            "rain" in lower -> R.drawable.ic_weather_rain
            "cloud" in lower -> R.drawable.ic_weather_cloud
            "sun" in lower || "clear" in lower -> R.drawable.ic_weather_sun
            "snow" in lower -> R.drawable.ic_weather_snow
            else -> R.drawable.ic_weather_unknown
        }
    }
}
