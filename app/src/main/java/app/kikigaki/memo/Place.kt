package app.kikigaki.memo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** 録音した場所。位置情報が取れなかった場合は記録しない */
data class Place(val lat: Double, val lng: Double, val address: String) {
    /** Googleマップでこの地点を開くURL */
    val mapsUrl: String get() = "https://www.google.com/maps/search/?api=1&query=$lat,$lng"

    val label: String get() = address.ifBlank { String.format(Locale.US, "%.5f, %.5f", lat, lng) }

    fun openInMaps(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

object Places {
    fun permissions() = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun granted(ctx: Context) = permissions().any {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    /** 今いる場所を取る(最大10秒)。許可がない・取れないときは null */
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): Place? {
        if (!granted(ctx)) return null
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val loc = runCatching {
            withTimeoutOrNull(10_000) {
                val cts = CancellationTokenSource()
                try {
                    client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).await()
                } finally {
                    cts.cancel()
                }
            }
        }.getOrNull() ?: runCatching { client.lastLocation.await() }.getOrNull() ?: return null
        return Place(loc.latitude, loc.longitude, address(ctx, loc.latitude, loc.longitude))
    }

    /** 緯度経度から「○○県○○市…」の住所を引く。引けなければ空 */
    @Suppress("DEPRECATION")
    private suspend fun address(ctx: Context, lat: Double, lng: Double): String = withContext(Dispatchers.IO) {
        runCatching {
            if (!Geocoder.isPresent()) return@runCatching ""
            val a = Geocoder(ctx, Locale.JAPAN).getFromLocation(lat, lng, 1)?.firstOrNull() ?: return@runCatching ""
            val line = a.getAddressLine(0) ?: ""
            line.removePrefix("日本、").replace(Regex("^〒\\d{3}-\\d{4}\\s*"), "").trim()
        }.getOrDefault("")
    }
}
