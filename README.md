# Simple Weather (Android, Kotlin + Jetpack Compose)

Current weather, a 24-hour forecast and a 7-day forecast for your location or any city you search for, plus optional daily and severe-weather notifications.

---

## 1. Architecture

```
app/src/main/java/com/ana/simpleweather/
├── WeatherApp.kt              Application: creates the notification channels
├── MainActivity.kt            Single activity; switches between Weather and Settings screens
├── data/
│   ├── WeatherApi.kt          Open-Meteo forecast and city-search calls (OkHttp + kotlinx.serialization)
│   ├── WeatherModels.kt       API response classes + the app's WeatherData/HourForecast/DayForecast
│   ├── WeatherCodes.kt        WMO weather code → text + emoji
│   ├── SevereWeather.kt       Finds thunderstorms / heavy rain / snow / strong gusts in the next 12 h
│   ├── SettingsRepository.kt  DataStore: units, notification settings, saved place, cached forecast
│   └── LocationProvider.kt    One-shot, battery-friendly location + city name
├── notifications/
│   ├── Notifier.kt            Channels and posting notifications
│   ├── WeatherWorkers.kt      DailyWeatherWorker + SevereWeatherWorker (WorkManager)
│   └── WorkScheduler.kt       Schedules or cancels the workers to match the settings
└── ui/
    ├── WeatherViewModel.kt    App state: loading, errors, cache fallback, search, settings
    ├── WeatherScreen.kt       Main screen, welcome card, city-search dialog
    ├── SettingsScreen.kt      Units, notification toggles, time picker, explanations
    └── theme/Theme.kt         Material 3 theme (wallpaper colors on Android 12+)
```

The app uses a simple MVVM setup: the screens read a `StateFlow` from `WeatherViewModel`, which talks to the `data` layer. The last forecast is cached, so with no internet the app still shows the most recent data and says it is saved data.

## 2. Weather API: Open-Meteo

* **No API key and no account.** Open-Meteo is free for non-commercial use, so the app contains no secrets and needs no setup.
* Endpoints: `api.open-meteo.com/v1/forecast` (weather) and `geocoding-api.open-meteo.com/v1/search` (city search).
* The data is licensed CC BY 4.0, so the app shows "Weather data by Open-Meteo.com".
* For **commercial** use (for example, publishing a paid app or an app with ads), Open-Meteo requires a paid API plan. You would then add your key **without hard-coding it**: put `OPEN_METEO_KEY=...` in `local.properties` (which git ignores), read it into a `buildConfigField` in `app/build.gradle.kts`, and send it as the `apikey` query parameter. A key inside an APK can still be extracted, so for a public app the safest option is to route requests through your own small server.

**About severe-weather alerts:** Open-Meteo doesn't publish official government warnings. The app works out "severe weather" from the forecast itself: thunderstorms, heavy rain or snow, freezing rain, and wind gusts of about 70 km/h or more in the next 12 hours. These are **not** official warnings. Always follow your national weather service (PAGASA in the Philippines).

## 3. Permissions and Android background rules

| Permission | Why | When it's asked |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Download forecasts | Never shown (granted automatically) |
| `ACCESS_COARSE_LOCATION` | City-level location for local weather | Only when you tap **Use my location** |
| `POST_NOTIFICATIONS` (Android 13+) | Show notifications | Only when you switch a notification toggle on |

* **No background location.** The app gets one approximate location fix (Wi-Fi or cell, low power) when you open the app, tap refresh, or tap the location button. Background notifications use the **last place you viewed**, so the app never needs "Allow all the time".
* If you deny location access, or location is turned off, the app shows a message and opens the city search.
* Notifications run through **WorkManager**. The daily update runs once every 24 hours from the time you pick, and the severe-weather check runs about every 3 hours. Both need internet and survive reboots.
* For battery reasons, Android (Doze, App Standby) can delay background work, so a daily update may arrive a few minutes late. If your phone has aggressive battery saving (common on Xiaomi, Oppo, Vivo, Realme, Huawei), set the app's battery usage to **Unrestricted** so notifications aren't skipped.
* If notifications are blocked, Settings shows a button that opens the system notification settings.

## 4. Build the debug APK

### Option A: Android Studio (easiest on a computer)
1. Install **Android Studio** (current stable version) from developer.android.com/studio.
2. **File → Open** → choose the `SimpleWeather` folder.
3. Wait for "Gradle sync" to finish. The first sync downloads Gradle and the Android SDK, which takes a while. If Studio offers to install a missing SDK platform (Android 15 / API 35), accept.
4. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
5. Click **locate** in the popup. The file is at `app/build/outputs/apk/debug/app-debug.apk`.

> If Studio suggests upgrading the Android Gradle Plugin, you can accept or skip; the project builds as-is.

### Option B: Command line
Requirements: JDK 17 or newer, and the Android SDK (installing Android Studio once is the simplest way to get it).
```bash
cd SimpleWeather
# Tell Gradle where the SDK is, if ANDROID_HOME isn't set:
echo "sdk.dir=/path/to/Android/Sdk" > local.properties   # Windows: sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```
The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Option C: No computer setup (GitHub builds it for you)
1. Create a free GitHub account and a new repository.
2. Upload the contents of the `SimpleWeather` folder, including the hidden `.github` folder. Uploading through `git push` is the most reliable way to include it.
3. Open the **Actions** tab. The "Build APK" workflow runs automatically, or you can click **Run workflow**.
4. When it shows a green check, open the run and download **SimpleWeather-debug-apk** under *Artifacts*. Unzip it to get `app-debug.apk`.

## 5. Install on your Android phone

**Without cables:** send `app-debug.apk` to your phone (Google Drive, email, Messenger, etc.) → tap it → when Android asks, allow **"Install unknown apps"** for the app you opened it from (Files, Chrome, Drive…) → **Install**. If Play Protect warns about an unknown developer, choose **Install anyway**. This warning is normal for a self-built debug app.

**With USB (adb):**
1. On the phone, open **Settings → About phone** and tap **Build number** 7 times to enable Developer options. Then turn on **USB debugging** in Developer options.
2. Connect the phone and run `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or just press **Run ▶** in Android Studio.

Requires Android 8.0 (API 26) or newer.

## 6. Assumptions and choices

* minSdk 26 (Android 8.0), target/compile SDK 35 (Android 15).
* Toolchain: Android Gradle Plugin 8.9.1, Kotlin 2.1.20, Gradle wrapper 8.14.3, Compose BOM 2024.12.01, JDK 17 bytecode.
* Approximate (coarse) location only, which is enough for weather and more private.
* °C uses km/h for wind; °F uses mph.
* Hourly view = the next 24 hours; daily view = 7 days, both in the location's own time zone.
* A debug APK is signed with Android's automatic debug key. To publish on the Play Store, you would create a release signing key (Build → Generate Signed App Bundle/APK).

## 7. Things you must do locally

* Nothing to configure: no API key and no account are needed.
* Have internet the first time you build. Gradle downloads its own distribution, the Android plugin and libraries from Google's and Maven Central's repositories.
* On first launch, tap **Use my location** (or search a city), then enable notifications in **Settings** if you want them.
