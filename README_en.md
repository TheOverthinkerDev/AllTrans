# ReAllTrans

[Tiếng Việt](README.md)

ReAllTrans is an Xposed module (libxposed API 102) that translates the interface of Android apps while they run, without modifying their APKs. It continues [AllTrans](https://github.com/akhilkedia/AllTrans) by Akhil Kedia (which only works up to Android 13) so it runs on Android 14 to 17, building on the port by [Chanhnh](https://github.com/Chanhnh/AllTrans).

- Package name: `ovtk.realltrans`
- Version: 3.1.0
- Android 10 or later; tested on a Redmi K40s (HyperOS, Android 17) with LSPosed 2.2.1 (API 102)

## Features

- Translates app text at runtime: normal text, input hints, notifications, WebView and text drawn by custom views.
- Three translation services: Google Translate, Microsoft Translate and VietPhrase (Chinese to Vietnamese, offline).
- Global settings for all apps, plus per-app settings when needed.
- Translation cache, refreshed when the service or languages change.
- Long translations can scroll back and forth instead of being cut with "…" (optional).
- Extra support for Alibaba apps (Taobao, Tmall, Xianyu…): DinamicX text, the tab bar and UC WebView pages, with a safe mode that only changes what is shown.
- Picks an app's own built-in language (Android 13+ per-app languages), even on ROMs that hide that screen.
- Asks LSPosed to add an app to the module scope when you choose to translate it.
- A settings screen for ReAllTrans itself: language, light/dark theme, module status, test translation, settings backup and restore.
- Interface in English, Vietnamese, Simplified and Traditional Chinese.

## Requirements

- Android 10 or later.
- A rooted device with LSPosed 2.1 or later, or another framework supporting libxposed API 102.
- Internet for Google Translate or Microsoft Translate. VietPhrase works offline.
- Choosing an app's built-in language needs Android 13 or later.

## Installation

1. Download `ReAllTrans-v3.1.0.apk` from [Releases](https://github.com/TheOverthinkerDev/ReAllTrans/releases) and install it.
2. Open LSPosed, enable the **ReAllTrans** module and select **System Framework** in its scope.
3. Reboot.
4. Open ReAllTrans and go to the **ReAllTrans** tab. **Framework** under module status should say it is active.
5. When ReAllTrans asks, allow it to run in the background (**Background** under module status). Otherwise Android may cut its network in the background and VietPhrase or the fallback translation will not work.

If you used the old AllTrans (package `akhil.alltrans`): ReAllTrans is a separate app and does not pick up the old settings. Back up your settings in the old AllTrans if it offers that, uninstall it and disable the old module in LSPosed so two modules do not translate at once, then restore the backup in ReAllTrans.

## Usage

1. **Translation** tab: choose the service, the source language and the target language.
2. **Apps** tab: tick the apps to translate. If an app is not in the LSPosed scope yet, LSPosed shows a notification asking for permission. Tap **Approve**. If it is not approved within about 20 seconds, the tick is removed again.
3. Force-stop and reopen the target app. The app's page has a **Force stop & reopen** button for this.

In the app list:

- Apps that are set up (translating, using a built-in language, or with their own settings) are always ticked and listed first.
- An amber tick means the app uses its built-in language instead of translation.
- Unticking asks first, then turns translation off, deletes the app's own settings, resets its language to the system default and removes it from the LSPosed scope.

On an app's page:

- **OverRide Global Settings**: use a service, languages and options just for this app.
- **App language**: only available when the app declares its languages to Android. It shows how many languages the app supports.
- Translation and the built-in language cannot be used together. While one is in use the other is greyed out, and tapping it says which one to turn off first.
- The **Alibaba apps** options are only on by default for Alibaba apps. For any other app they start off.

## Privacy

- ReAllTrans has no server of its own and collects no usage data.
- With Google Translate or Microsoft Translate, the text to translate is sent to that service, usually directly from the app being translated.
- VietPhrase translates entirely on the device.
- `INTERNET` is used for the online services. `QUERY_ALL_PACKAGES` is used to list apps for you to choose from.
- Root is only used when the system hook is not working, to change an app's language or force-stop an app.

## How it works

- LSPosed loads the module into apps in its scope. The module hooks the places where text is shown (such as `TextView.setText`, `StaticLayout`, notifications, WebView), sends the text for translation and puts the translation in its place.
- Google Translate and Microsoft Translate are called inside the target app, because the app in front is not network-restricted by the system. VietPhrase, and Google/Microsoft when that fails, run in ReAllTrans through ContentProviders. Target apps reach them through a proxy in the system process, which is why the **System Framework** scope is needed.
- ReAllTrans copies its settings into the framework with [libxposed/service](https://github.com/libxposed/service). Target apps read them back with `getRemotePreferences()` from [libxposed/api](https://github.com/libxposed/api) instead of querying a provider on every start.
- Changing an app's built-in language and force-stopping apps happen in the system process through the hook, or through root when the hook is not available.

## Project structure

See [docs/PROJECT_STRUCTURE_en.md](docs/PROJECT_STRUCTURE_en.md) for what each file does and how to build.

## Troubleshooting

See [docs/TROUBLESHOOTING_en.md](docs/TROUBLESHOOTING_en.md).

## Contributing

- Report bugs or suggestions in [Issues](https://github.com/TheOverthinkerDev/ReAllTrans/issues).
- Include your Android version, ROM, LSPosed version, the target app's name and version, and the steps that cause the problem. If possible, turn on **Advanced: Enable debug mode** and attach `adb logcat -s AllTrans`.
- To change code: fork the repository, create a branch and open a pull request.

## Credits

- **Original:** [AllTrans](https://github.com/akhilkedia/AllTrans) by Akhil Kedia.
- **VietPhrase from Chanhnh:** the VietPhrase engine, the libxposed API 102 port and more from [Chanhnh/AllTrans](https://github.com/Chanhnh/AllTrans). VietPhrase rules and some data come from [duongden/script-vietphrase-translator](https://github.com/duongden/script-vietphrase-translator).
- **libxposed:** [libxposed/api](https://github.com/libxposed/api) and [libxposed/service](https://github.com/libxposed/service) (Apache-2.0).

License: GPL-3.0-or-later, see [LICENSE.GPL](LICENSE.GPL).
