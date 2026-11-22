# Project structure

[Tiếng Việt](PROJECT_STRUCTURE.md) | [Back to README](../README_en.md)

One Android application module (`app`), written in Kotlin. Sources are in `app/src/main/kotlin/akhil/alltrans/`. The Kotlin package stays `akhil.alltrans` to keep the code history; the application ID is `ovtk.realltrans`.

## Code that runs inside target apps (the module)

| File | Role |
| --- | --- |
| `Alltrans.kt` | Module entry point (`XposedModule`). Installs hooks when an app starts; in the system process it hooks `SettingsProvider` as a proxy |
| `HookRuntime.kt` | Wrapper around the libxposed API: hooks, calling the original method, logging, reading remote preferences |
| `AppOnCreateHookHandler.kt`, `AttachBaseContextHookHandler.kt` | Read settings when the app starts, manage the cache, install the translation hooks |
| `PreferenceList.kt`, `PreferenceManager.kt` | Settings in effect for the app (global and per app) |
| `SetTextHookHandler.kt`, `GetTranslate.kt` | Translate `TextView` text and input hints |
| `StaticLayoutHookHandler.kt`, `LayoutConstructorHookHandler.kt`, `ViewTextMethodHookHandler.kt`, `CustomViewSetTextHookHandler.kt` | Extended translation for self-drawn text and custom views |
| `NotificationHookHandler.kt` | Translate notifications |
| `VirtWebViewOnLoad.kt`, `WebViewClientWrapper.kt`, `WebViewOnCreateHookHandler.kt`, `WebViewSetClientHookHandler.kt` | Translate WebView pages |
| `TaobaoHooks.kt`, `UcWebViewTranslator.kt`, `MtopTranslator.kt` | Alibaba app support: DinamicX text, tab bar, UC WebView, MTOP data |
| `MarqueeHelper.kt` | Scrolls long translations back and forth |
| `GetTranslateToken.kt` | Sends text for translation (Google/Microsoft inside the target app, the provider as fallback), handles the cache |
| `TranslateHttp.kt` | Shared HTTP client for Google/Microsoft: short timeouts, drops stale connections, retries once |
| `SystemBridge.kt` | Changes app languages and force-stops apps from the system process (or through root) |

## Code that runs in the ReAllTrans app

| File | Role |
| --- | --- |
| `AllTransApp.kt` | `Application` class, connects to the libxposed service when the app opens |
| `RemotePrefs.kt` | Copies settings into the framework with libxposed/service |
| `AppConfig.kt` | Per-app state: translating, built-in language, LSPosed scope; ReAllTrans background permission |
| `MainActivity.kt` | Main screen and navigation |
| `AppListFragment.kt` | App list |
| `LocalPreferenceFragment.kt` | Per-app settings page |
| `GlobalPreferencesFragment.kt` | Global translation settings |
| `AppSettingsFragment.kt`, `AppSettings.kt` | ReAllTrans settings, module status, backup, test translation, credits |
| `LockablePreferences.kt` | Settings that can be locked (greyed out, but explain why when tapped) |
| `GtransProvider.kt`, `EdgeProvider.kt`, `VietPhraseProvider.kt` | Google, Microsoft and VietPhrase translation services |
| `VietPhraseTranslator.kt`, `VietPhraseTranslationEngine.kt`, `VietPhraseRuleEngine.kt` | VietPhrase engine |
| `SharedPrefProvider.kt` | Fallback settings channel when the framework has no remote preferences |

## Resources

| Path | Contents |
| --- | --- |
| `app/src/main/res/xml/` | Settings screens, ReAllTrans language list |
| `app/src/main/res/values*/strings.xml` | UI text: English, Vietnamese, Simplified and Traditional Chinese |
| `app/src/main/assets/vietphrase/` | VietPhrase dictionaries and rules |
| `app/src/main/resources/META-INF/xposed/` | libxposed module declaration: entry class, minimum API, default scope |
| `app/src/test/` | Unit tests |

## Build

Requires JDK 17 and the Android SDK platform `android-37.0` (libxposed/service 102 needs compileSdk 37; targetSdk stays 36).

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Outputs:

- `app/build/outputs/apk/debug/ReAllTrans-v3.1.0-debug.apk`
- `app/build/outputs/apk/release/ReAllTrans-v3.1.0.apk`

To sign the release with your own key, set these in `gradle.properties`:

```properties
RELEASE_STORE_FILE=/path/to/keystore.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

libxposed libraries:

- `io.github.libxposed:api:102.0.0` (`compileOnly`, provided by the framework at runtime)
- `io.github.libxposed:service:102.0.0` (bundled in the APK)
