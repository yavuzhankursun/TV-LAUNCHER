<div align="center">

# Sade Home

**A lightweight, cinematic, Apple TV-like home screen for the Xiaomi Mi Box S.**
Replaces Google TV's heavy launcher; switch back to the stock UI with one click whenever you want.

**English** | [Türkçe](README.tr.md)

![Android TV 14](https://img.shields.io/badge/Android%20TV-14-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
![No dependencies](https://img.shields.io/badge/dependencies-none-555)
![APK](https://img.shields.io/badge/APK-~250%20KB-555)

<img src="docs/ekran/ana-ekran-2.jpg" alt="Home screen" width="860">

</div>

---

## Highlights

- **Cinematic home screen:** a full-screen backdrop in the selected app's brand color and a large, crisp logo. Cards spring up on focus with a light sweep across them.
- **Clean icons:** brand marks from [Simple Icons](https://simpleicons.org) (CC0) with official brand colors. Apps without a mark get a white silhouette generated from their logo.
- **Built-in AirPlay:** screen mirroring and audio from iPhone, iPad and Mac. No extra app needed. The TV **asks for permission** before a new device connects.
- **Smooth mode:** hands RAM to the app you open and closes the ones in the background. You choose which apps to protect.
- **Top bar:** clock, date, weather, RAM meter, AirPlay status and a speed test.
- **Browser:** a web browser Google TV lacks; the remote's arrow keys drive a mouse cursor.
- **Lightweight:** no androidx, no Compose, no third-party libraries. The launcher itself uses ~50–60 MB of RAM, versus ~140 MB for the Google TV home screen.

## Screenshots

| | |
|:-:|:-:|
| <img src="docs/ekran/ana-ekran.jpg" alt="Home screen with TV+ selected" width="420"> | <img src="docs/ekran/uygulama-menusu.jpg" alt="Long-press menu" width="420"> |
| Home screen | Long-press menu |
| <img src="docs/ekran/airplay.jpg" alt="AirPlay panel" width="420"> | <img src="docs/ekran/ayarlar.jpg" alt="Settings panel" width="420"> |
| AirPlay | Settings |

---

## Installation

**Requirements:** JDK 17+, Android SDK (platform 35), `adb`. The box and your computer must be on the same network.

1. **Enable developer mode on the box:** Settings → System → About → tap *Android TV OS build* 7 times. Then Developer options → **USB debugging: On**.
2. **Build and install:**
   ```bash
   ./gradlew assembleRelease
   adb connect <BOX_IP>:5555
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```
3. **First launch:** Google TV → Apps → **Sade Home** → ⚙ → **ADB → Connect**. In the dialog that appears on the TV, tick *"Always allow"* and confirm.

That is all. The app sets up the HOME redirect and the permissions it needs by itself.

> `local.properties` must contain `sdk.dir=/path/to/Android/sdk`. Android Studio creates this file for you.

## Usage

| Remote | What happens |
|---|---|
| ◀ ▶ | Move between apps |
| ▲ ▼ | Top bar ↔ apps |
| OK | Open |
| Hold OK / Menu | Move, hide, force stop, protect in Smooth mode, uninstall |
| Back | Closes the panel |
| HOME | Returns to this home screen from anywhere |

**Going back to the stock UI:** ⚙ → *Return to the Google TV launcher*. To come back: Google TV → Apps → Sade Home → ⚙ → *Use this home screen*.

## AirPlay

- **Screen mirroring:** on iPhone/iPad, Control Center → **Screen Mirroring** → *"(TV name) AirPlay"*. On a Mac: menu bar → Control Center → Screen Mirroring.
- **Audio only:** while music is playing, AirPlay icon → TV.
- **Permission:** when a new device connects, the TV asks **Deny / Allow once / Always allow**. If there is no answer within 30 seconds, the request is denied. Remembered devices can be removed under ⚙ → AirPlay.
- **Ending a session:** *Stop Mirroring* on the iPhone, or the **Back** key on the TV.

## Smoothness

**Smooth mode** (⚙ → Smoothness, on by default): about 4 seconds after you open an app, it closes whatever is left in the background. Long-press an app you want to keep and choose **Protect in Smooth mode**; the Play Store is protected by default.

Measured on a Mi Box S:

| | Result |
|---|---|
| Navigating the home screen | **~1%** dropped frames |
| Netflix (with 3 apps open in the background) | missed frames **52% → 5%** (with Smooth mode) |

Tip: with Settings → Display & Sound → Advanced display → **Match content frame rate** enabled, films (24 fps) play without judder.

<details>
<summary><b>Speed up the TV</b>: disable unnecessary preinstalled apps</summary>

⚙ → Smoothness → **Speed up the TV**. Apps are **not deleted**; they are disabled with `pm disable-user` and can be re-enabled with one click (or with *Re-enable all*).

| Recommended | If you do not use them |
|---|---|
| Xiaomi Gallery, Xiaomi Media Player | YouTube Kids, YouTube Music |
| Google Feedback, Play Games | Miracast |
| Print service, legacy screensavers | Ambient mode |
| TalkBack | Google TV recommendation rows |

**Never touched:** streaming apps, Play Store, Google Play Services, voice search, Chromecast, keyboard, the remote-control service and the Google TV home screen.
</details>

<details>
<summary><b>Troubleshooting</b></summary>

| Problem | Fix |
|---|---|
| HOME key goes to Google TV | ⚙ → *HOME key redirect*. If the app is "force stopped", Android disables this service. The first HOME press right after an update may also slip through. |
| TV not visible on the iPhone | Are the TV and the phone on the same Wi‑Fi? Is ⚙ → AirPlay on (the dot on the chip should be green)? It will not show up on guest networks or on routers with "client isolation" enabled. |
| Smooth mode not working | ⚙ → ADB must show *Connected*. The ADB port may close after the box reboots: `adb connect <BOX_IP>:5555`, then `adb tcpip 5555`. |
| No weather | Hidden when there is no internet. ⚙ → Weather → pick the city manually. |
| No picture while mirroring | Check the output of `adb logcat -s SadeAirPlay`. |

**Uninstalling completely:** `adb uninstall com.hizir.sadehome`. The HOME redirect and permissions reset on their own. Apps disabled with "Speed up the TV" stay disabled, so press *Re-enable all* before uninstalling.
</details>

<details>
<summary><b>Project structure</b></summary>

```
app/src/main/java/com/hizir/sadehome/
├── LauncherActivity.kt      Home screen: layout, focus, panels
├── HomeActivity.kt          Invisible HOME router (new launcher / Google TV)
├── HomeWatcherService.kt    Accessibility service: bypasses Google TV's HOME lock
├── adb/                     Dependency-free ADB client (connects to its own box)
├── airplay/                 Built-in AirPlay receiver: RTSP, pairing, FairPlay, H.264, AAC/ALAC, permission dialog
├── browser/                 Browser (WebView + remote-driven cursor)
├── data/                    App list, clean icons (Marks), settings, weather, speed test
├── system/                  Smooth mode, Speed up the TV, HOME control
└── ui/                      Card, scrolling row, backdrop, side panel, drawn icons
```

Kotlin 2.0.21 · AGP 8.7.3 · Gradle 8.11.1 · minSdk 26 · targetSdk 34.
</details>

---

## License and credits

- `airplay/FairPlay.kt` is a Kotlin port of the *playfair* code in [UxPlay](https://github.com/FDH2/UxPlay) and is licensed under **GPL-3.0**. The AirPlay protocol flow was also written with UxPlay as the reference. If the app is distributed, its source code must therefore be shared under GPL-3.0.
- Brand marks come from [Simple Icons](https://simpleicons.org) (CC0); logos belong to their owners.
- Built for personal use. Not affiliated with Google, Xiaomi or Apple.

<div align="center"><sub>Built by <b>Yavuzhan Kurşun</b></sub></div>
