# RetroTV

An Android TV app for playing retro games without installing emulators. It's a streamlined TV browser built around in-browser emulation sites, with a built-in player for the games those sites serve.

RetroTV contains no games, ROMs or BIOS files. It opens third-party websites and plays files you load or that those sites provide. You are responsible for only playing games you have the right to play.

## What it does

- **16 built-in game sites** (RetroGames.cc, PlayClassic, ClassicReload, Emulator Gamer, Retro Games Nexus, Internet Arcade, OldGames, Rec0deD:88 and more). You can add your own.
- **Remote and controller friendly:** the D-pad drives an on-screen pointer, or sends keys. Game controllers work through the Gamepad API or mapped keys, and a phone can act as a controller or upload ROMs over Wi-Fi (scan the QR code).
- **Built-in player** (EmulatorJS): takes over when a site starts an EmulatorJS game. Four save slots with screenshots, rewind, fast-forward, cheats, shader filters, controller remapping and offline play for cached cores.
- **"Game not working? Fix it":** one button in the quick menu that works through the usual fixes (player choice, desktop or mobile view, ad block, controller mode) and remembers what worked for that site.
- **Library:** every game you play, with box art, play time and favourites. A "Continue playing" card and "Surprise me" button on the home screen.
- **Ad, pop-up and redirect blocking** that leaves game files and navigation alone. It can be switched off per site.
- **Full screen, windowed and "Focus game"** modes, with desktop or mobile view per site.
- **Install any built-in site as its own TV app**, with its own banner and sandboxed sign-ins.
- **Userscripts** with one-click Greasy Fork search, emulator APK downloads from official sources, backup and restore, and TV-to-TV save sync.

Download the latest signed APK and see the full feature list at <https://tdater.github.io/RetroTV-releases/>.

## Building

Native Kotlin and Jetpack Compose, single `:app` module. Android 7.0+ (minSdk 24), target SDK 37. Use the JDK bundled with Android Studio:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs the same checks on every push; the `app-debug` artifact contains the APK. A `v*` tag builds a signed release; the signing key lives outside this repository.

## Licence

Copyright © 2026 travisdater. All rights reserved. This is a personal project, provided as-is with no support.
