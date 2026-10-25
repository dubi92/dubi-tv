# tv-launcher

Custom lightweight Android TV home launcher with its own design and animations, for an older TV (low RAM/GPU).

**Dubi TV** is the launcher for a Sony BRAVIA 2015 (Android TV 8.0, API 26, armeabi-v7a, 1.6 GB RAM).
It draws the approved pixel-art mockup (`data/tvl-design-r3/mockups/b-pixel.html`, spec in `HANDOFF.md`):
the Da Nang backdrop by time of day, the chibi avatar walking under the focused app, the five dock
cartridges, the app grid, the HDMI / Wi-Fi / Settings quick panels, the launch overlay, the screensaver,
the boot sequence and the weekend Dragon Bridge show.

## Build

Needs JDK 17 and an Android SDK with platform 34 and build-tools 34 (`local.properties` -> `sdk.dir`).

```sh
./gradlew :app:testReleaseUnitTest :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk (signed with the debug key)
```

## Install and make it HOME

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
# The stock launcher's HOME filter has a system priority, so a third-party HOME only wins once it is disabled:
adb shell pm disable-user --user 0 com.google.android.tvlauncher
adb shell input keyevent KEYCODE_HOME
# Undo: adb shell pm enable com.google.android.tvlauncher
```

## How it is built

- One plain `Activity` (no AndroidX, no Compose) hosting one custom `View` (`LauncherView`) that draws the
  whole 1920x1080 stage on a hardware canvas. Pixel art is decoded at 1x and scaled by the GPU with
  nearest-neighbour sampling at whole-number scales (backdrop 8x, avatar 4x, logotype 4x/8x/16x).
- Every animation is stepped, as in the mockup. Instead of a 60 fps loop each animation reports when its
  next step lands (`Frame`), and the view sleeps until then, on a shared 40 ms grid. All loops pause 5 s
  after the last key, while an app is open and on the screensaver, so an idle Home draws no frames.
- Apps come from `PackageManager` (leanback launch intents). The dock takes YouTube, Netflix, FPT Play,
  VieON and Spotify with their hand-drawn icons; VTV Go and HDViet fill slots of dock apps that are not
  installed. Every other app's icon is pixelised once per app version (`Pixelizer`, a port of
  `mockups/pixelize.js`) on a background thread and cached as a 16x16 PNG; muddy results get the
  brand-colour fallback tile.
- No `INTERNET` permission, no analytics, no services. Only `ACCESS_WIFI_STATE` / `ACCESS_NETWORK_STATE`
  for the Wi-Fi panel's status line.

## Debug keys (USB/Bluetooth keyboard or `adb shell input keyevent`)

`S` screensaver now, `R` replay boot, `T` cycle day/dusk/night, `F` dragon show, `G` travel pose.

## Emulator stand-ins

`tools/stubapps` builds tiny stand-in apps with the real package names and labels (YouTube, Netflix,
FPT Play, VieON, Spotify, VTV Go, HDViet) for checking the dock and launch/back on an emulator:
`./gradlew :stubapps:assembleDebug`. Never install them on the TV.
