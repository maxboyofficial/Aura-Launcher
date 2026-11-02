# Aura Launcher

A fast, minimal Android home launcher built with Jetpack Compose.

## Get the APK (no Android Studio needed)
1. Create a new **GitHub repository** and upload / push everything in this folder.
2. Open the **Actions** tab -> **Build APK** -> wait ~5 min (or click *Run workflow*).
3. Open the finished run -> **Artifacts** -> download `aura-launcher-apk`.
4. Inside, use `release/app-release.apk` (fast, optimised). Copy it to your phone and install it
   (allow "Install unknown apps" when asked).
5. Press the Home button -> choose **Aura** -> **Always**.
   (Or: Settings -> Apps -> Default apps -> Home app -> Aura.)

## Or build with Android Studio
Open this folder -> let Gradle sync -> Build > Build Bundle(s)/APK(s) > Build APK(s).

## Using it
- Swipe up anywhere on home -> app drawer. Pull down at the top of the list (or drag the handle) to close.
- Type in the drawer search; press Go to open the first match.
- Long-press any app: add/remove from Home or Dock, App info, Uninstall.
- Dock holds 5 apps, Home holds 12. Dock is pre-filled with Phone, Messages, Browser, Camera when found.
- To go back to your old launcher: Settings -> Apps -> Default apps -> Home app.
