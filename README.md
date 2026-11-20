# FireMouse

A virtual mouse for Fire TV, controlled with the normal Fire TV remote.
Tested on a **Fire TV Stick 4K Max (2nd gen), Fire OS 8.1.8.5** (Android 11).

Built because [MATVT](https://github.com/virresh/matvt) shows its cursor on newer Fire OS but its clicks do nothing.
FireMouse is adapted from MATVT and keeps its idea of an overlay cursor plus remote key handling, but clicks differently (see [How it works](#how-it-works)).

## Remote controls

| Button | Action |
|---|---|
| Menu ☰ | Mouse on / off |
| D-pad | Move the cursor (hold to speed up) |
| OK | Click under the arrow tip |
| Push past a screen edge | Scroll that way |
| ⏪ / ⏩ | Scroll up / down |
| Back, Home, Play | Work as usual |

The arrow flashes green when a click or scroll landed and red when there was nothing there.
While the mouse is on, the Menu button is used as the on/off key and doesn't reach apps.

## Install

You need a PC with [adb](https://developer.android.com/tools/releases/platform-tools) and the Fire TV on the same network.

1. On the Fire TV: **Settings → My Fire TV → Developer options → ADB debugging → On**.
   (No Developer options? Go to **Settings → My Fire TV → About** and click your device name 7 times.)
2. Find the Fire TV's IP: **Settings → My Fire TV → About → Network**.
3. Download `FireMouse.apk` from the [Releases](../../releases) page.
4. On the PC, replace `<FIRE-TV-IP>` with your IP and run:

```
adb connect <FIRE-TV-IP>:5555
adb -s <FIRE-TV-IP>:5555 install -r FireMouse.apk
adb -s <FIRE-TV-IP>:5555 shell settings put secure enabled_accessibility_services dev.firemouse/.MouseService
```

The first `adb connect` shows an "Allow USB debugging?" prompt on the TV. Choose **Always allow** and **OK**.

That's it. Open an app (for example a browser) and press **Menu ☰**. The service stays enabled after reboots, and the PC is only needed once.

> The last command replaces any other accessibility services you have enabled (for example MATVT).
> Run `adb shell settings get secure enabled_accessibility_services` first if you want to see what's there,
> and join entries with `:` to keep several.

To remove it: `adb -s <FIRE-TV-IP>:5555 uninstall dev.firemouse`

## How it works

Two things that remote-mouse apps normally rely on are blocked on Fire OS 8:

- **Accessibility gestures**: `AccessibilityService.dispatchGesture()` is accepted, then cancelled about 1 ms later. That's why MATVT's cursor moves but can't click.
- **ADB to itself**: adbd hangs up on ADB connections that start on the device itself (127.0.0.1 or its own IP), so an app can't send `input tap` through its own ADB.

So FireMouse doesn't fake touches. It finds the UI element under the arrow tip and asks it to click or scroll itself
(`AccessibilityNodeInfo.performAction`). This works for normal app screens and for web pages in Chromium-based browsers (tested in Brave).

Chromium reports element boxes that don't always sit inside their parent's box (CSS floats, fixed and absolute positioning),
so the search under the cursor runs in up to three passes: strict, tolerant, then a full walk. Most clicks resolve in 0.1–0.5 s. See [`NodeActions.kt`](app/src/main/java/dev/firemouse/NodeActions.kt).

**Limits:** things without accessibility info won't react. That includes games, canvas-drawn pages and some video-player controls. The Fire TV home screen also ignores it; use the D-pad there (Menu toggles the mouse off).

## Build from source

Requires JDK 17+ and the Android SDK (Android Studio installs both).

```
./gradlew assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/app-debug.apk`.

## Testing from a PC

Real remote presses can't be injected over adb, so there's a test hook. Only the adb shell can trigger it, not other apps:

```
adb shell am broadcast -a dev.firemouse.TEST --es cmd toggle
adb shell am broadcast -a dev.firemouse.TEST --es cmd click --ei x 960 --ei y 540
adb shell am broadcast -a dev.firemouse.TEST --es cmd scroll --es dir DOWN --ei x 960 --ei y 540
adb logcat -s FireMouse
```

## License

GPL-3.0, like MATVT, which it's derived from. See [LICENSE](LICENSE).

Made by Sound Wave with Claude Code.
