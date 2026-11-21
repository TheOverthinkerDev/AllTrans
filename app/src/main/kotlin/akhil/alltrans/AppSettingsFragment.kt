package akhil.alltrans

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "AllTrans" tab: settings of the app itself, module status, data tools, about. */
class AppSettingsFragment : PreferenceFragmentCompat() {

    private val main = Handler(Looper.getMainLooper())
    private var serviceObserver: ((io.github.libxposed.service.XposedService?) -> Unit)? = null

    override fun onDestroy() {
        serviceObserver?.let { RemotePrefs.removeObserver(it) }
        super.onDestroy()
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { exportTo(it) }
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { confirmImport(it) }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = AppSettings.PREFS
        setPreferencesFromResource(R.xml.app_settings, rootKey)

        setupAppearance()
        setupStatus()
        setupData()
        findPreference<Preference>("TestTranslate")?.setOnPreferenceClickListener { showTestTranslate(); true }
        setupAbout()
    }

    // --------------------------------------------------------------- appearance

    private fun setupAppearance() {
        findPreference<ListPreference>("UiLanguage")?.apply {
            // Current value comes from AppCompat (per-app language), not from prefs.
            value = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',')
                .let { tag -> if (tag in entryValues.map { it.toString() }) tag else "" }
            setOnPreferenceChangeListener { _, newValue ->
                val tag = newValue as String
                AppCompatDelegate.setApplicationLocales(
                    if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
                )
                true
            }
        }
        findPreference<ListPreference>(AppSettings.KEY_THEME)?.setOnPreferenceChangeListener { _, newValue ->
            AppCompatDelegate.setDefaultNightMode(
                when (newValue) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
            true
        }
        findPreference<Preference>(AppSettings.KEY_DYNAMIC_COLOR)?.setOnPreferenceChangeListener { _, _ ->
            main.post { activity?.recreate() }
            true
        }
    }

    // ------------------------------------------------------------------- status

    private fun setupStatus() {
        val appContext = requireContext().applicationContext
        val framework = findPreference<Preference>("StatusFramework")
        val settingsSync = findPreference<Preference>("StatusSettingsSync")
        serviceObserver = { service ->
            if (isAdded) {
                if (service != null) {
                    // libxposed/service: framework identity and this module's scope.
                    val scope = runCatching { service.scope }.getOrNull().orEmpty()
                    framework?.summary = getString(R.string.status_framework_active, RemotePrefs.describe(service)) +
                        "\n" + getString(R.string.status_scope, scope.size, scope.joinToString())
                    settingsSync?.summary = getString(
                        if (RemotePrefs.remoteSupported) R.string.status_sync_remote else R.string.status_sync_provider
                    )
                } else {
                    framework?.summary = getString(R.string.status_framework_inactive)
                    settingsSync?.summary = getString(R.string.status_sync_none)
                }
            }
        }
        RemotePrefs.observe(serviceObserver!!)

        val global = requireContext().getSharedPreferences("AllTransPref", Context.MODE_PRIVATE)
        val enabledApps = enabledPackages(global).size
        findPreference<Preference>("StatusApps")?.summary = getString(
            if (global.getBoolean("Enabled", true)) R.string.status_apps_on else R.string.status_apps_off,
            enabledApps
        )

        findPreference<Preference>("StatusRoot")?.apply {
            setOnPreferenceClickListener { pref ->
                pref.summary = getString(R.string.app_language_loading)
                Thread {
                    val root = SystemBridge.hasRoot()
                    main.post { if (isAdded) pref.summary = getString(if (root) R.string.status_root_yes else R.string.status_root_no) }
                }.start()
                true
            }
        }
    }

    // --------------------------------------------------------------------- data

    private fun setupData() {
        findPreference<Preference>("ClearAllCache")?.setOnPreferenceClickListener {
            val context = requireContext()
            val global = context.getSharedPreferences("AllTransPref", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis().toString()
            val packages = enabledPackages(global)
            for (pkg in packages) {
                context.getSharedPreferences(pkg, Context.MODE_PRIVATE).edit().putString("ClearCacheTime", now).apply()
                PreferenceManager.clearCache(pkg)
            }
            PreferenceManager.clearCache()
            Thread { RemotePrefs.syncAll() }.start()
            Toast.makeText(context, getString(R.string.clear_all_cache_done, packages.size), Toast.LENGTH_LONG).show()
            true
        }
        findPreference<Preference>("Export")?.setOnPreferenceClickListener {
            val date = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            exportLauncher.launch("AllTrans-backup-$date.json")
            true
        }
        findPreference<Preference>("Import")?.setOnPreferenceClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            true
        }
    }

    /** Names of every SharedPreferences file of AllTrans settings (global, per app, app UI). */
    private fun settingFiles(context: Context): List<String> {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        val names = dir.listFiles()?.map { it.name.removeSuffix(".xml") }.orEmpty()
        // Global prefs, the app's own prefs, and one file per app (package names contain dots).
        return names.filter { it == "AllTransPref" || it == AppSettings.PREFS || (it.contains('.') && !it.startsWith("akhil.alltrans")) }
    }

    private fun exportTo(uri: Uri) {
        val context = requireContext()
        try {
            val root = JSONObject().put("format", "alltrans-backup").put("version", 1)
            val files = JSONObject()
            for (name in settingFiles(context)) {
                val entries = JSONObject()
                for ((key, value) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                    val typed = when (value) {
                        is Boolean -> JSONObject().put("t", "b").put("v", value)
                        is Int -> JSONObject().put("t", "i").put("v", value)
                        is Long -> JSONObject().put("t", "l").put("v", value)
                        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                        is String -> JSONObject().put("t", "s").put("v", value)
                        is Set<*> -> JSONObject().put("t", "ss").put("v", org.json.JSONArray(value.map { it.toString() }))
                        else -> null
                    }
                    if (typed != null) entries.put(key, typed)
                }
                files.put(name, entries)
            }
            root.put("prefs", files)
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(root.toString(2).toByteArray()) }
            Toast.makeText(context, getString(R.string.backup_export_done, files.length()), Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Toast.makeText(context, getString(R.string.backup_failed, t.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmImport(uri: Uri) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup_import)
            .setMessage(R.string.backup_import_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> importFrom(uri) }
            .show()
    }

    private fun importFrom(uri: Uri) {
        val context = requireContext()
        try {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalStateException("cannot read file")
            val root = JSONObject(text)
            require(root.optString("format") == "alltrans-backup") { "not an AllTrans backup" }
            val files = root.getJSONObject("prefs")
            for (name in files.keys()) {
                if (!(name == "AllTransPref" || name == AppSettings.PREFS || name.contains('.'))) continue
                val entries = files.getJSONObject(name)
                val editor: SharedPreferences.Editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                for (key in entries.keys()) {
                    val e = entries.getJSONObject(key)
                    when (e.getString("t")) {
                        "b" -> editor.putBoolean(key, e.getBoolean("v"))
                        "i" -> editor.putInt(key, e.getInt("v"))
                        "l" -> editor.putLong(key, e.getLong("v"))
                        "f" -> editor.putFloat(key, e.getDouble("v").toFloat())
                        "s" -> editor.putString(key, e.getString("v"))
                        "ss" -> {
                            val arr = e.getJSONArray("v")
                            editor.putStringSet(key, (0 until arr.length()).map { arr.getString(it) }.toSet())
                        }
                    }
                }
                editor.commit()
            }
            PreferenceManager.clearCache()
            Thread { RemotePrefs.syncAll() }.start()
            Toast.makeText(context, getString(R.string.backup_import_done, files.length()), Toast.LENGTH_LONG).show()
            activity?.recreate()
        } catch (t: Throwable) {
            Toast.makeText(context, getString(R.string.backup_failed, t.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------- test translate

    private fun showTestTranslate() {
        val context = requireContext()
        val input = EditText(context).apply {
            setText("你好，世界！欢迎使用 AllTrans。")
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(context).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.test_translate)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.test_translate_run) { _, _ -> runTestTranslate(input.text.toString()) }
            .show()
    }

    private fun runTestTranslate(text: String) {
        if (text.isBlank()) return
        val appContext = requireContext().applicationContext
        val global = appContext.getSharedPreferences("AllTransPref", Context.MODE_PRIVATE)
        // Use the global translation settings, as a target app would.
        val provider = global.getString("TranslatorProvider", "g") ?: "g"
        val vietPhrase = provider == "vietphrase"
        val from = if (vietPhrase) "zh" else global.getString("TranslateFromLanguage", "auto") ?: "auto"
        val to = if (vietPhrase) "vi" else global.getString("TranslateToLanguage", "vi") ?: "vi"
        val description = "$provider: $from → $to"
        Toast.makeText(appContext, R.string.app_language_loading, Toast.LENGTH_SHORT).show()
        Thread {
            val start = System.currentTimeMillis()
            // Query the module's own translation provider directly. (The hook
            // classes need the Xposed framework and cannot run in this app.)
            val result = try {
                val authority = when (provider) {
                    "edge" -> "akhil.alltrans.EdgeProvider"
                    "vietphrase" -> "akhil.alltrans.VietPhraseProvider"
                    else -> "akhil.alltrans.GtransProvider"
                }
                val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
                val uri = Uri.parse("content://$authority?from=${enc(from)}&to=${enc(to)}&text=${enc(text)}")
                appContext.contentResolver.query(uri, arrayOf("translate"), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(c.getColumnIndex("translate").coerceAtLeast(0)) else null
                }
            } catch (t: Throwable) {
                null
            }
            val ms = System.currentTimeMillis() - start
            main.post {
                if (!isAdded) return@post
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.test_translate)
                    .setMessage(
                        if (result.isNullOrEmpty()) getString(R.string.test_translate_failed, description)
                        else getString(R.string.test_translate_result, result, description, ms)
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }.start()
    }

    // -------------------------------------------------------------------- about

    private fun setupAbout() {
        val context = requireContext()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        findPreference<Preference>("Version")?.summary = "${info.versionName} (${info.longVersionCode})"
        val links = mapOf(
            "Source" to "https://github.com/TheOverthinkerDev/AllTrans",
            "CreditsOriginal" to "https://github.com/akhilkedia/AllTrans",
            "CreditsVietPhrase" to "https://github.com/Chanhnh/AllTrans",
            "CreditsLibxposed" to "https://github.com/libxposed"
        )
        for ((key, url) in links) {
            findPreference<Preference>(key)?.setOnPreferenceClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                true
            }
        }
    }

    companion object {
        /** Apps with translation turned on: package-name keys set to true in the global prefs. */
        fun enabledPackages(global: SharedPreferences): List<String> =
            global.all.filter { (key, value) -> value == true && key.contains('.') }.keys.toList()
    }
}
