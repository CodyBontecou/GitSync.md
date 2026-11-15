# Scrawl development captures

The iOS Debug app links the official [ScrawlKit SDK](https://github.com/kitlangton/scrawl), pinned to `6d4fa333475ef5170c4df683e306480f8618b95a`. Debug builds require iOS 18; Release retains its existing deployment target and does not install Scrawl gestures or handle pairing links. ScrawlKit is inert until the owner pairs a device.

1. Run Scrawl on your Mac and complete its macOS permission prompts.
2. Build and install the iOS app with the Debug configuration.
3. Run `scrawl link --scheme syncmd` on the Mac and scan its QR code/open its link on that phone. The link includes a private pairing token; do not commit it.
4. Hold three fingers on the app for half a second to start or finish. Allow the microphone and Local Network prompts when recording/sending.
5. Use `scrawl inspect latest` on the Mac, or paste the capture into your agent with Option-Shift-V.

To unpair, open `syncmd://scrawl?reset=1`. For USB fallback, the Mac's Scrawl config lists this app under `usbApps`. A new Debug build must be installed before pairing works; the existing App Store app does not gain this SDK from a source change.

ScrawlKit captures the app's UI and narration only after the owner starts a capture. Treat recordings as potentially containing the data currently visible on screen. No export schema, product data grants or app automation contract changes. On macOS use Scrawl's global screen/window capture.

## Android (debug builds)

Android debug builds pick up ScrawlKit for Android (`dev.kitlangton.scrawl:scrawlkit-autoinstall:0.1.0`) when it is in your local Maven repository. It installs itself at process start, so the app has no ScrawlKit code. The SDK is not published to a public repository yet, so the dependency is local-only:

- Publish it once with `./gradlew publishToMavenLocal` from `android/` in a checkout of the Scrawl repository that contains the Android port. That puts it in `~/.m2/repository/dev/kitlangton/scrawl/`.
- `android/app/build.gradle.kts` adds the dependency only when that directory exists, so CI and other machines build without it. Release builds never include it (`debugImplementation`).
- `mavenLocal` in `android/settings.gradle.kts` is limited to the `dev.kitlangton.scrawl` group.

Pairing uses its own scheme, `syncmdscrawl`, so ScrawlKit's link handler does not overlap the app's `syncmd://` intent filters:

1. Build and install the debug app (`./gradlew :app:installDebug`; see the Android build notes for JDK 17).
2. Run `scrawl link --scheme syncmdscrawl` on the Mac and open the link on the device, or send it over adb: `adb shell am start -a android.intent.action.VIEW -d "'syncmdscrawl://scrawl?url=…&token=…'"`. Don't commit the token.
3. Hold three fingers on the app to start or finish a recording, and allow the microphone prompt.

The debug network security config allows plain HTTP only to `localhost`, `127.0.0.1` and `10.0.2.2` (the Mac as seen from the emulator, `http://10.0.2.2:47777`). Your Mac's LAN or Tailscale addresses are not committed. To reach a physical device, use USB: add `com.bontecou.syncmd.debug` to `usbApps` in `~/.config/scrawl/config.json` so Scrawl pulls recordings over adb, or run `adb reverse tcp:47777 tcp:47777` and pair with `http://127.0.0.1:47777`. To unpair, open `syncmdscrawl://scrawl?reset=1`.
