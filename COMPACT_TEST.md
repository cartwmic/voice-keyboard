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
