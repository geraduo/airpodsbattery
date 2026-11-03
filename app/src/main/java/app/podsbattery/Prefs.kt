package app.podsbattery

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.util.Locale

object Prefs {
    const val DEFAULT_LANG = "en"
    private const val FILE = "settings"
    private const val KEY_LANG = "lang"
    private const val KEY_LAST = "last"
    private const val KEY_DARK = "dark"
    private const val KEY_NOTIF_ASKED = "notif_asked"
    const val KEY_POPUP = "popup"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun lang(c: Context): String = prefs(c).getString(KEY_LANG, DEFAULT_LANG) ?: DEFAULT_LANG
    fun setLang(c: Context, code: String) = prefs(c).edit().putString(KEY_LANG, code).apply()

    fun dark(c: Context): Boolean = prefs(c).getBoolean(KEY_DARK, false)
    fun setDark(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_DARK, on).apply()

    fun popup(c: Context): Boolean = prefs(c).getBoolean(KEY_POPUP, true)
    fun setPopup(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_POPUP, on).apply()
    fun listen(c: Context, l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(c).registerOnSharedPreferenceChangeListener(l)
    fun unlisten(c: Context, l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(c).unregisterOnSharedPreferenceChangeListener(l)

    fun notifAsked(c: Context): Boolean = prefs(c).getBoolean(KEY_NOTIF_ASKED, false)
    fun setNotifAsked(c: Context) = prefs(c).edit().putBoolean(KEY_NOTIF_ASKED, true).apply()

    fun saveLast(c: Context, s: PodsState, time: Long) {
        val parts = listOf(s.model, s.left ?: -1, s.right ?: -1, s.case ?: -1,
            s.chargingLeft, s.chargingRight, s.chargingCase, time)
        prefs(c).edit().putString(KEY_LAST, parts.joinToString("|")).apply()
    }

    fun loadLast(c: Context): Pair<PodsState, Long>? {
        val p = prefs(c).getString(KEY_LAST, null)?.split("|") ?: return null
        if (p.size != 8) return null
        return try {
            PodsState(
                model = p[0],
                left = p[1].toInt().takeIf { it >= 0 },
                right = p[2].toInt().takeIf { it >= 0 },
                case = p[3].toInt().takeIf { it >= 0 },
                chargingLeft = p[4].toBoolean(),
                chargingRight = p[5].toBoolean(),
                chargingCase = p[6].toBoolean()
            ) to p[7].toLong()
        } catch (_: Exception) {
            null
        }
    }

    fun localized(c: Context): Context {
        val config = Configuration(c.resources.configuration)
        config.setLocale(Locale(lang(c)))
        return c.createConfigurationContext(config)
    }
}
