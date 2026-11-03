package pl.czysty.launcher

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Pogoda dla Krakowa: bez konta i bez lokalizacji urządzenia (współrzędne są stałe).
 * Źródła po kolei: Open-Meteo, met.no, wttr.in — gdy jedno nie odpowiada, bierzemy następne.
 * Każde jest mapowane na kody pogody WMO (jak w Open-Meteo), więc ikony są wspólne.
 * [fetch] wołać z wątku w tle. Ostatni wynik jest w SharedPreferences, więc po starcie
 * pigułka od razu ma co pokazać.
 */
class Weather(context: Context) {
    class Now(val tempC: Int, val code: Int, val isDay: Boolean, val fetchedAt: Long) {
        val isStale get() = System.currentTimeMillis() - fetchedAt > REFRESH_MS

        val icon: Int
            get() = when (code) {
                0, 1, 2 -> if (isDay) R.drawable.ic_sun else R.drawable.ic_moon
                45, 48 -> R.drawable.ic_fog
                in 51..67, in 80..82 -> R.drawable.ic_rain
                in 71..77, 85, 86 -> R.drawable.ic_snow
                in 95..99 -> R.drawable.ic_storm
                else -> R.drawable.ic_cloud
            }

        val text get() = "$tempC°"
    }

    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun cached(): Now? {
        val parts = prefs.getString(KEY, null)?.split(';') ?: return null
        if (parts.size != 4) return null
        return Now(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: 3, parts[2] == "1", parts[3].toLongOrNull() ?: 0L)
    }

    fun fetch(): Now? {
        val now = attempt("Open-Meteo", ::fromOpenMeteo)
            ?: attempt("met.no", ::fromMetNo)
            ?: attempt("wttr.in", ::fromWttr)
            ?: return null
        prefs.edit().putString(KEY, "${now.tempC};${now.code};${if (now.isDay) 1 else 0};${now.fetchedAt}").apply()
        return now
    }

    private fun attempt(name: String, source: () -> Now?): Now? = try {
        source()
    } catch (e: Exception) {
        Log.w(TAG, "Pogoda ($name): ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private fun fromOpenMeteo(): Now? {
        val current = JSONObject(get(OPEN_METEO) ?: return null).getJSONObject("current")
        return Now(
            tempC = current.getDouble("temperature_2m").roundToInt(),
            code = current.optInt("weather_code", 3),
            isDay = current.optInt("is_day", 1) == 1,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    /** met.no: pierwsza pozycja prognozy to bieżąca godzina; symbol np. "partlycloudy_night", "lightrain". */
    private fun fromMetNo(): Now? {
        val first = JSONObject(get(MET_NO) ?: return null)
            .getJSONObject("properties").getJSONArray("timeseries").getJSONObject(0).getJSONObject("data")
        val temp = first.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature")
        val symbol = (first.optJSONObject("next_1_hours") ?: first.optJSONObject("next_6_hours"))
            ?.getJSONObject("summary")?.optString("symbol_code") ?: "cloudy"
        val base = symbol.substringBefore('_')
        val code = when {
            "thunder" in base -> 95
            "snow" in base -> 71
            "rain" in base || "sleet" in base -> 61
            base == "fog" -> 45
            base == "clearsky" || base == "fair" -> 0
            base == "partlycloudy" -> 2
            else -> 3
        }
        val isDay = when {
            symbol.endsWith("_day") -> true
            symbol.endsWith("_night") -> false
            else -> isDaytimeNow()
        }
        return Now(temp.roundToInt(), code, isDay, System.currentTimeMillis())
    }

    /** wttr.in: kody WWO (113 = bezchmurnie itd.). */
    private fun fromWttr(): Now? {
        val c = JSONObject(get(WTTR) ?: return null).getJSONArray("current_condition").getJSONObject(0)
        val code = when (val wwo = c.optInt("weatherCode", 119)) {
            113 -> 0
            116 -> 2
            143, 248, 260 -> 45
            200, 386, 389, 392, 395 -> 95
            179, 227, 230, 323, 326, 329, 332, 335, 338, 350, 368, 371, 374, 377 -> 71
            in 176..399 -> if (wwo == 119 || wwo == 122) 3 else 61
            else -> 3
        }
        return Now(c.getString("temp_C").toDouble().roundToInt(), code, isDaytimeNow(), System.currentTimeMillis())
    }

    /** Przybliżenie dnia/nocy dla źródeł, które go nie podają (tylko do wyboru słońce/księżyc). */
    private fun isDaytimeNow(): Boolean {
        val c = Calendar.getInstance()
        val month = c.get(Calendar.MONTH)
        val hour = c.get(Calendar.HOUR_OF_DAY)
        val (sunrise, sunset) = if (month in Calendar.APRIL..Calendar.SEPTEMBER) 5 to 20 else 7 to 16
        return hour in sunrise until sunset
    }

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            // met.no wymaga identyfikacji aplikacji w User-Agent.
            conn.setRequestProperty("User-Agent", "CzystyLauncher/3.0 (Android TV launcher)")
            if (conn.responseCode != 200) {
                Log.w(TAG, "Pogoda: HTTP ${conn.responseCode} z ${conn.url.host}")
                return null
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val REFRESH_MS = 30 * 60 * 1000L
        private const val KEY = "weather"
        private const val TAG = "CzystyLauncher"
        private const val OPEN_METEO = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=50.0614&longitude=19.9366&current=temperature_2m,weather_code,is_day&timezone=auto"
        private const val MET_NO = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=50.06&lon=19.94"
        private const val WTTR = "https://wttr.in/Krakow?format=j2"
    }
}
