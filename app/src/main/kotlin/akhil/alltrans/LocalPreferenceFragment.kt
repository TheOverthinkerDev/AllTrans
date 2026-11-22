package akhil.alltrans

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import java.text.Collator
import java.util.TreeMap

class LocalPreferenceFragment : PreferenceFragmentCompat() {
    var applicationInfo: ApplicationInfo? = null
    private var globalSettings: SharedPreferences? = null

    private fun configureTranslatorProviderPreference(preference: ListPreference?) {
        preference ?: return
        preference.entries = resources.getTextArray(R.array.translatorProviderNames)
        preference.summaryProvider = Preference.SummaryProvider<ListPreference> { listPreference ->
            when (listPreference.value) {
                "g" -> getString(R.string.translator_provider_google).lineSequence().firstOrNull()?.trim()
                "edge" -> getString(R.string.translator_provider_microsoft).lineSequence().firstOrNull()?.trim()
                PreferenceList.VIETPHRASE_PROVIDER ->
                    getString(R.string.translator_provider_vietphrase).lineSequence().firstOrNull()?.trim()
                else -> {
                    Log.e("AllTrans", "LocalPref: Unsupported translator provider: ${listPreference.value}")
                    null
                }
            }
        }
    }

    private fun sortListPreferenceByEntries(preference: ListPreference?) {
        preference ?: return
        val entries = preference.entries ?: return
        val entryValues = preference.entryValues ?: return

        if (entries.size != entryValues.size) {
            Log.e("AllTrans", "LocalPref: Mismatch entries/entryValues for: ${preference.key}")
            return
        }

        val sortRules = Collator.getInstance(resources.configuration.locales.get(0))
        sortRules.strength = Collator.PRIMARY
        val sorter = TreeMap<CharSequence?, CharSequence?>(sortRules)
        for (i in entries.indices) {
            if (!sorter.containsKey(entries[i])) {
                sorter.put(entries[i], entryValues[i])
            }
        }

        val sortedLabels = arrayOfNulls<CharSequence>(sorter.size)
        val sortedValues = arrayOfNulls<CharSequence>(sorter.size)
        var i = 0
        for (entry in sorter.entries) {
            sortedLabels[i] = entry.key
            sortedValues[i] = entry.value
            i++
        }

        val currentValue = preference.value
        preference.entries = sortedLabels
        preference.entryValues = sortedValues
        preference.value = currentValue
    }

    private fun applyLanguagePreference(
        preference: ListPreference?,
        namesRes: Int,
        codesRes: Int,
        defaultLanguage: String,
        enabled: Boolean
    ) {
        preference ?: return
        preference.entries = resources.getTextArray(namesRes)
        preference.entryValues = resources.getTextArray(codesRes)
        sortListPreferenceByEntries(preference)

        val currentValue = preference.value
        val entryValues = preference.entryValues
        if (currentValue == null || entryValues == null || entryValues.none { it == currentValue }) {
            Log.w(
                "AllTrans",
                "LocalPref: Resetting unsupported language for ${preference.key}: $currentValue -> $defaultLanguage"
            )
            preference.value = defaultLanguage
        }
        preference.isEnabled = enabled
    }

    private fun applyVietPhraseLanguages(
        translateFromLanguage: ListPreference?,
        translateToLanguage: ListPreference?
    ) {
        translateFromLanguage?.apply {
            entries = arrayOf(getText(R.string.Chinese_google))
            entryValues = arrayOf(PreferenceList.VIETPHRASE_SOURCE_LANGUAGE)
            value = PreferenceList.VIETPHRASE_SOURCE_LANGUAGE
            isEnabled = false
        }
        translateToLanguage?.apply {
            entries = arrayOf(getText(R.string.Vietnamese_google))
            entryValues = arrayOf(PreferenceList.VIETPHRASE_TARGET_LANGUAGE)
            value = PreferenceList.VIETPHRASE_TARGET_LANGUAGE
            isEnabled = false
        }
    }

    private fun updateLanguageLists(
        provider: String?,
        translateFromLanguage: ListPreference?,
        translateToLanguage: ListPreference?,
        enabled: Boolean
    ) {
        when (provider) {
            PreferenceList.VIETPHRASE_PROVIDER -> {
                applyVietPhraseLanguages(translateFromLanguage, translateToLanguage)
            }

            "g" -> {
                applyLanguagePreference(
                    translateFromLanguage,
                    R.array.languageNamesGoogle,
                    R.array.languageCodesGoogle,
                    PreferenceList.DEFAULT_SOURCE_LANGUAGE,
                    enabled
                )
                applyLanguagePreference(
                    translateToLanguage,
                    R.array.languageNamesGoogle,
                    R.array.languageCodesGoogle,
                    PreferenceList.DEFAULT_TARGET_LANGUAGE,
                    enabled
                )
            }

            "edge" -> {
                applyLanguagePreference(
                    translateFromLanguage,
                    R.array.languageNames,
                    R.array.languageCodes,
                    PreferenceList.DEFAULT_SOURCE_LANGUAGE,
                    enabled
                )
                applyLanguagePreference(
                    translateToLanguage,
                    R.array.languageNames,
                    R.array.languageCodes,
                    PreferenceList.DEFAULT_TARGET_LANGUAGE,
                    enabled
                )
            }

            else -> {
                Log.e("AllTrans", "LocalPref: Unsupported translator provider: $provider")
                translateFromLanguage?.isEnabled = false
                translateToLanguage?.isEnabled = false
            }
        }
    }

    private fun enableLocalPrefs(enable: Boolean) {
        // Everything that only applies with "override global settings" on.
        // (The language lists are handled by updateLanguageLists.)
        val keysToToggle = listOf(
            "TranslatorProvider", "SetText", "SetHint", "ExtendedViewTranslation",
            "LoadURL", "Notif", "Cache", "Scroll", "Delay", "DelayWebView", "InProcessTranslate"
        )
        keysToToggle.forEach { key ->
            findPreference<Preference>(key)?.isEnabled = enable
        }
    }

    private fun requestCacheClear(localPrefs: SharedPreferences?, packageName: String?) {
        localPrefs?.edit()
            ?.putString("ClearCacheTime", System.currentTimeMillis().toString())
            ?.apply()
        PreferenceManager.clearCache(packageName)
    }

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        globalSettings = requireActivity().getSharedPreferences("AllTransPref", Context.MODE_PRIVATE)

        if (applicationInfo == null) {
            val safeContext = context
            if (safeContext != null) {
                Toast.makeText(safeContext, R.string.wut_why_null, Toast.LENGTH_SHORT).show()
            }
            Log.e("AllTrans", "LocalPreferenceFragment: applicationInfo is null!")
            parentFragmentManager.popBackStack()
            return
        }

        val prefManager = preferenceManager
        prefManager.sharedPreferencesName = applicationInfo!!.packageName
        // Mirror this app's settings to libxposed remote preferences on change.
        RemotePrefs.watch(applicationInfo!!.packageName)
        val localPrefs = prefManager.sharedPreferences

        addPreferencesFromResource(R.xml.perappprefs)

        val isGloballyEnabled = globalSettings?.contains(applicationInfo!!.packageName) ?: false
        val overrideEnabled = localPrefs?.getBoolean("OverRide", false) ?: false

        findPreference<SwitchPreference>("LocalEnabled")?.isChecked = isGloballyEnabled
        translationOn = isGloballyEnabled
        findPreference<SwitchPreference>("OverRide")?.isChecked = overrideEnabled

        enableLocalPrefs(overrideEnabled)

        val translatorProvider = findPreference<ListPreference>("TranslatorProvider")
        val translateFromLanguage = findPreference<ListPreference>("TranslateFromLanguage")
        val translateToLanguage = findPreference<ListPreference>("TranslateToLanguage")

        configureTranslatorProviderPreference(translatorProvider)

        // [override] is passed in: inside OverRide's change listener the switch
        // still holds its old value, so reading isChecked there inverted the state.
        fun refreshLanguageLists(override: Boolean = findPreference<SwitchPreference>("OverRide")?.isChecked == true) {
            val localOverrideEnabled = override
            updateLanguageLists(
                translatorProvider?.value,
                translateFromLanguage,
                translateToLanguage,
                localOverrideEnabled
            )
        }

        translatorProvider?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
            val newProvider = newValue as? String
            val oldProvider = (preference as? ListPreference)?.value
            updateLanguageLists(
                newProvider,
                translateFromLanguage,
                translateToLanguage,
                findPreference<SwitchPreference>("OverRide")?.isChecked == true
            )
            if (oldProvider != newProvider) {
                requestCacheClear(localPrefs, applicationInfo?.packageName)
                Toast.makeText(requireContext(), R.string.clear_cache_provider, Toast.LENGTH_SHORT).show()
            }
            true
        }

        refreshLanguageLists()

        findPreference<SwitchPreference>("OverRide")?.setOnPreferenceChangeListener { _, newValue ->
            val override = newValue as Boolean
            enableLocalPrefs(override)
            refreshLanguageLists(override)
            true
        }

        InProcessWarning.attach(findPreference("InProcessTranslate"))

        findPreference<Preference>("ClearCache")?.setOnPreferenceClickListener { preference ->
            localPrefs?.edit()
                ?.putString("ClearCacheTime", System.currentTimeMillis().toString())
                ?.apply()
            Toast.makeText(preference.context, R.string.clear_cache_success, Toast.LENGTH_SHORT).show()
            true
        }

        translateFromLanguage?.setOnPreferenceChangeListener { _, _ ->
            requestCacheClear(localPrefs, applicationInfo?.packageName)
            true
        }
        translateToLanguage?.setOnPreferenceChangeListener { _, _ ->
            requestCacheClear(localPrefs, applicationInfo?.packageName)
            true
        }

        setupAppLanguage(applicationInfo!!.packageName)
        setupForceStop(applicationInfo!!.packageName)

        // Alibaba switches are not persistent: a value is stored only when the
        // user flips it, otherwise the app follows the effective default. Only
        // Alibaba apps follow the global default; for others it is off.
        val pkgName = applicationInfo!!.packageName
        val alibaba = AppConfig.isAlibabaApp(pkgName)
        for ((key, default) in listOf("AlibabaOptimize" to true, "SafeMode" to true, "MtopTranslate" to false)) {
            findPreference<SwitchPreference>(key)?.apply {
                val effectiveDefault = if (key == "AlibabaOptimize" && !alibaba) false
                    else globalSettings?.getBoolean(key, default) ?: default
                isChecked = localPrefs?.getBoolean(key, effectiveDefault) ?: effectiveDefault
                setOnPreferenceChangeListener { _, newValue ->
                    localPrefs?.edit()?.putBoolean(key, newValue as Boolean)?.apply()
                    true
                }
            }
        }

        setupScope(pkgName)

        findPreference<SwitchPreference>("LocalEnabled")?.setOnPreferenceChangeListener { pref, newValue ->
            val enabled = newValue as Boolean
            if (enabled) {
                // On only once the app is in the LSPosed scope; the switch flips
                // back if the scope request is denied or not answered in time.
                AppConfig.enableTranslation(requireContext(), pkgName) { ok ->
                    if (!isAdded) return@enableTranslation
                    (pref as SwitchPreference).isChecked = ok
                    translationOn = ok
                    refreshScope(pkgName)
                    updateLocks()
                }
            } else {
                AppConfig.setTranslation(requireContext(), pkgName, false)
            }
            // Translation and the built-in language exclude each other.
            translationOn = enabled
            updateLocks()
            true
        }

        // Any change the user makes here keeps the app ticked in the list.
        localPrefs?.registerOnSharedPreferenceChangeListener(configuredListener)
        updateLocks()
    }

    private val configuredListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val pkg = applicationInfo?.packageName ?: return@OnSharedPreferenceChangeListener
        if (key != null && key != "Configured" && key != "LocalEnabled" && key != "ClearCacheTime" && key != AppConfig.KEY_BUILT_IN_LANGUAGE) {
            context?.let { AppConfig.markConfigured(it, pkg) }
        }
    }

    override fun onDestroy() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(configuredListener)
        super.onDestroy()
    }

    /** Current per-app language of the app ("" = system default, null = unknown). */
    private var currentAppLocale: String? = null
    private var translationOn = false
    private var appLanguageSupported = false
    private var appDeclaresLocales = false
    private var appLanguagePref: LockableListPreference? = null
    private var appLanguageSummary: ((String?) -> Unit)? = null

    /**
     * Translation and the built-in language are mutually exclusive: while one
     * is in use the other is greyed out and explains how to free it.
     */
    private fun updateLocks() {
        if (!isAdded) return
        val builtIn = appDeclaresLocales && !currentAppLocale.isNullOrEmpty()
        findPreference<LockableSwitchPreference>("LocalEnabled")?.lockedMessage =
            if (builtIn && !translationOn) getString(R.string.lock_translation_built_in) else null
        appLanguagePref?.lockedMessage =
            if (translationOn && !builtIn && appLanguageSupported) getString(R.string.lock_built_in_translation) else null
    }

    // ------------------------------------------------------------------ scope

    private fun setupScope(pkg: String) {
        val pref = findPreference<Preference>("Scope") ?: return
        pref.setOnPreferenceClickListener {
            AppConfig.requestScopeIfNeeded(requireContext(), pkg)
            true
        }
        refreshScope(pkg)
    }

    private fun refreshScope(pkg: String) {
        val pref = findPreference<Preference>("Scope") ?: return
        pref.summary = getString(
            when (AppConfig.isInScope(pkg)) {
                true -> R.string.scope_in
                false -> R.string.scope_not_in
                null -> R.string.scope_no_service
            }
        )
    }

    override fun onResume() {
        super.onResume()
        // The user may have approved the scope request in the framework meanwhile.
        applicationInfo?.packageName?.let { pkg ->
            Thread {
                AppConfig.refreshScope()
                android.os.Handler(android.os.Looper.getMainLooper()).post { if (isAdded) refreshScope(pkg) }
            }.start()
        }
    }

    /** "Force stop & reopen": applies changed settings without going to system settings. */
    private fun setupForceStop(pkg: String) {
        val pref = findPreference<Preference>("ForceStop") ?: return
        pref.setOnPreferenceClickListener {
            val appContext = requireContext().applicationContext
            pref.isEnabled = false
            Thread {
                val result = SystemBridge.forceStop(appContext, pkg)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    pref.isEnabled = true
                    if (result.ok) {
                        appContext.packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
                            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            // Give the process a moment to die before relaunching.
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                try { appContext.startActivity(launch) } catch (_: Throwable) {}
                            }, 400)
                        }
                        Toast.makeText(appContext, R.string.force_stop_done, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(appContext, appContext.getString(R.string.force_stop_failed, result.error ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
            true
        }
    }

    /**
     * "App language": the app's own built-in translations through Android's
     * per-app language setting (no machine translation involved).
     */
    private fun setupAppLanguage(pkg: String) {
        val pref = findPreference<LockableListPreference>("AppLanguage") ?: return
        appLanguagePref = pref
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            pref.isEnabled = false
            pref.summary = getString(R.string.app_language_unsupported)
            return
        }
        pref.isEnabled = false
        val appContext = requireContext().applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())

        fun describe(tags: String?): String =
            if (tags.isNullOrEmpty()) getString(R.string.app_language_system_default)
            else tags.split(',').joinToString(", ") { SystemBridge.displayName(it) }

        Thread {
            val locales = SystemBridge.appLocales(appContext, pkg)
            val current = SystemBridge.getLocales(appContext, pkg)
            main.post {
                if (!isAdded) return@post
                val currentTag = current.value.orEmpty().substringBefore(',')
                currentAppLocale = if (current.ok) currentTag else null
                val supported = locales.support == SystemBridge.LocaleSupport.SUPPORTED
                // Offer only what the app declares. If the app is not eligible but a
                // language is set anyway, still allow going back to the system language.
                val tags = when {
                    supported -> locales.tags
                    currentTag.isNotEmpty() -> listOf(currentTag)
                    else -> emptyList()
                }
                pref.entries = (listOf(getString(R.string.app_language_system_default)) +
                    tags.map { SystemBridge.displayName(it) }).toTypedArray()
                pref.entryValues = (listOf("") + tags).toTypedArray()
                pref.value = currentTag
                pref.isEnabled = current.ok && (supported || currentTag.isNotEmpty())
                appLanguageSupported = supported || currentTag.isNotEmpty()
                appDeclaresLocales = supported
                // Follow a language changed elsewhere (system settings). A locale on an
                // app that declares no languages is a leftover, not a built-in language.
                if (current.ok) AppConfig.setBuiltInLanguage(appContext, pkg, currentTag.takeIf { supported && it.isNotEmpty() })
                updateLocks()

                val reason = when (locales.support) {
                    SystemBridge.LocaleSupport.SUPPORTED -> null
                    SystemBridge.LocaleSupport.NOT_DECLARED -> getString(R.string.app_language_not_declared)
                    SystemBridge.LocaleSupport.PARSE_FAILED -> getString(R.string.app_language_parse_failed)
                    SystemBridge.LocaleSupport.OS_TOO_OLD -> getString(R.string.app_language_unsupported)
                    SystemBridge.LocaleSupport.ERROR -> getString(R.string.app_language_check_failed, locales.error ?: "")
                }
                val available = if (supported) getString(R.string.app_language_available, locales.tags.size) else null
                appLanguageSummary = { tag ->
                    pref.summary = listOfNotNull(
                        if (!current.ok) getString(R.string.app_language_unavailable, current.error ?: "")
                        else getString(R.string.app_language_current, describe(tag)),
                        reason,
                        available
                    ).joinToString("\n")
                }
                appLanguageSummary?.invoke(currentTag)
            }
        }.start()

        pref.setOnPreferenceChangeListener { _, newValue ->
            val tag = newValue as? String ?: ""
            pref.isEnabled = false
            Thread {
                val result = SystemBridge.setLocales(appContext, pkg, tag)
                main.post {
                    if (!isAdded) return@post
                    pref.isEnabled = true
                    if (result.ok) {
                        val applied = result.value.orEmpty().substringBefore(',')
                        currentAppLocale = applied
                        pref.value = applied
                        appLanguageSummary?.invoke(applied)
                        AppConfig.setBuiltInLanguage(appContext, pkg, applied)
                        updateLocks()
                        if (applied.isNotEmpty()) {
                            // The app shows its own translation: AllTrans hooks are not
                            // needed there any more, so leave its LSPosed scope.
                            Thread { AppConfig.removeScope(appContext, pkg); main.post { if (isAdded) refreshScope(pkg) } }.start()
                        }
                        Toast.makeText(appContext, getString(R.string.app_language_applied, describe(result.value)), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(appContext, getString(R.string.app_language_unavailable, result.error ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
            false // value is set from the actual result above
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }
}
