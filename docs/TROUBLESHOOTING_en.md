# Troubleshooting

[Tiếng Việt](TROUBLESHOOTING.md) | [Back to README](../README_en.md)

Before trying the steps below, open the **ReAllTrans** tab and look at the module status. It shows whether the module is running and how settings reach target apps.

## An app is not translated

- Check that **Framework** in the ReAllTrans tab says it is active. If not, enable the module in LSPosed, select **System Framework** in its scope and reboot.
- Check that the app is in the LSPosed scope. On the app's page, **LSPosed scope** shows the state. Tap it to send the request again.
- Check that the **Master On/Off** in the Translation tab and **Translate This App** on the app's page are both on.
- Force-stop and reopen the target app (**Force stop & reopen**).
- If the app uses its built-in language (amber tick), set **App language** back to **System default** before turning translation on.

## The tick disappears after ticking an app

LSPosed needs your approval to add the app to the scope. If you do not tap **Approve** within about 20 seconds, or you tap **Deny**, ReAllTrans does not turn translation on. Tick it again and tap **Approve** in the LSPosed notification. If no notification appears, open LSPosed and add the app to the ReAllTrans scope yourself.

## App language cannot be changed

- Android 13 or later is required.
- The app must declare its languages to Android. Otherwise the option is locked and shows the reason. This is a limit of the app and cannot be fixed from ReAllTrans.
- If it says "Not available", the system hook is not running. Enable the System Framework scope and reboot, or grant root to ReAllTrans.

## Google Translate or Microsoft Translate does not work

- Check the network connection.
- Use **Test translation** in the ReAllTrans tab to check the selected service.
- Try the other service.

## VietPhrase does not translate

- VietPhrase only translates Chinese to Vietnamese.
- Use **Test translation** to check it.

## Some text is still not translated

- Text inside images, or games that draw their own text, cannot be translated.
- Try turning on **Extended View Translation**.
- Try a higher delay before replacing text or before translating WebViews.
- Taobao: some screens (such as the cart) are only translated with **Safe mode** off and **Translate network data (MTOP, experimental)** on. This option may affect the app.

## The target app crashes or closes after enabling translation

- Turn off **Scroll Long Translated Text**.
- Turn off **Translate Notifications** or **Translate Text in WebView** if you do not need them.
- For non-Alibaba apps, turn off **Optimize for Alibaba apps** if it is on.
- Clear the translation cache and force-stop the target app.
- If it still fails, untick the app in the list and report the problem with a log.

## After moving from the old AllTrans to ReAllTrans

- Disable the old AllTrans module in LSPosed or uninstall the old app. Two active modules translate on top of each other.
- ReAllTrans is a new app, so its LSPosed scope and settings must be set up again, or restored from a backup.

## Getting a log for a bug report

1. Turn on **Advanced: Enable debug mode** in the Translation tab.
2. Force-stop and reopen the target app and repeat the steps that cause the problem.
3. On a computer, run `adb logcat -s AllTrans` and save the output.
4. Attach the log when opening an [Issue](https://github.com/TheOverthinkerDev/ReAllTrans/issues).

Note: some ROMs (such as HyperOS) filter low-level logs from third-party apps. Logs from the system hook are still visible.
