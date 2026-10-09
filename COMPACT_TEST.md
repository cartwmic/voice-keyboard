# Compact Terminal keyboard test

This fork adds an optional, fixed-height row: keyboard switch, Talk/Stop, backspace, Enter.
The default full keyboard remains available. Provider, model, language, transcription and
post-processing settings work as before; enabling the compact layout does not alter them.

## Install and try

The debug APK is a side-by-side test application named **Voice Keyboard Compact Test**,
with application ID `com.tyraen.voicekeyboard.compacttest`. It does not replace the signed
upstream application, and cannot read that application's private settings. Keep the existing
app installed. Enable the test keyboard and configure the same homelab endpoint, model and
key (or placeholder for a server without authentication) in the test app once.

In the test app's settings, enable **Compact Terminal layout**. The change saves immediately,
without pressing the endpoint's Apply button. Start at 64 dp; the slider allows 56–72 dp.
The next keyboard appearance uses the selected layout and height. System navigation and
reported display-cutout safe space are additional to this height.

- Tap Talk to record and Stop to queue transcription.
- Working replaces the label in place. You can tap it to start another queued recording.
- Hold the mic area during recording to cancel that recording.
- If saved recordings are waiting, hold the mic area to retry them.
- Tap the keyboard-switch key to return to the previous keyboard; hold it to open settings.
- Backspace supports the existing accelerating hold. Enter retains the app's field-aware
  behavior, including ordinary Enter/newline behavior in terminals.
- Clipboard, language, punctuation, post-processing toggles, and manual deletion of failed
  recordings are available in the full layout. Existing configured processing still applies
  in compact mode. To change these controls, disable Compact Terminal layout in settings.

Debug builds intentionally hide the upstream update controls: upstream release APKs cannot
update this separately signed package. Install future test APKs signed with the same test key
over this test app. The debug signing key is only a development key, not a production identity.

## Optional camera-space experiment

After trying ordinary compact mode, enable **Use space beside cameras (experimental)** if you
want to try the cover screen's camera band. On Android 11 or later, the IME requests permission
to lay out into the cutout region and uses Android's reported camera bounding rectangles. It
keeps a 4 dp margin from them and only places the single row alongside them when at least
260 dp is free (wide Talk plus three 48 dp keys). Otherwise the row stays above the cameras.
It never switches to two rows or shrinks targets. The setting defaults off. Disable it to return
to ordinary system-safe layout. Android 10 and earlier retain the ordinary safe layout.

Phone software can still limit the keyboard window independently of the host app's fullscreen
setting. A camera rectangle may enclose both lenses, so space between lenses is not assumed
usable. The keyboard deliberately does not use Android's fullscreen extracted-text editor,
which would replace the host editor rather than merely extend the keyboard into the camera area.

If the placement is unexpected, open settings and tap **Copy keyboard layout diagnostics**.
This copies the latest local measurement: display, window/input bounds, cutout and navigation
insets, window mode and placement reason. It contains no typed text, audio, provider address or
API key and is not uploaded automatically. Share it with a screenshot of the cover screen if you
want help adjusting the test. Physical camera clearance still requires testing on the Razr.

## Build and automated checks

Use JDK 17 and Android SDK 34 with the repository's Gradle 8.5 wrapper:

```
./gradlew testDebugUnitTest assembleDebug lintDebug
```

The APK is `app/build/outputs/apk/debug/VoiceKeyboard.apk`. Release identity/signing behavior
is unchanged. No upstream keystore or credentials are included in this work.

Robolectric tests measure the actual compact view hierarchy across 56/64/72 dp heights,
recording, queue processing, clipboard updates, retries, errors and password blocking. They
also check 48 dp minimum button targets, row ordering and display-cutout padding. Preference
unit tests cover defaults and height bounds. Existing repository tests remain in the suite.

## Device checks still required

On the Moto Razr 2024 cover screen, check idle → recording → Working → ready in an SSH terminal,
then backspace hold, Enter, keyboard switching, long-press settings, screen folding/unfolding,
and returning from settings after changing height. Verify that camera/rounded-corner regions
and gesture or 3-button navigation do not obscure keys at each height. Also check full-layout
restoration, light/dark themes and large font settings. Automated layout tests do not establish
physical camera clearance or validate the private homelab endpoint from this environment.

## Validation of this test build

Validated in the cloud build environment on 2026-10-09 with JDK 17, Android SDK 34 and Gradle 8.5:
242 automated tests passed; debug APK assembly and Android lint passed. Lint reports zero errors
and 69 warnings, including existing project warnings. The debug APK signature and separate
application identity were verified. No physical Razr, microphone session, TalkBack session or
private homelab request was tested here.
