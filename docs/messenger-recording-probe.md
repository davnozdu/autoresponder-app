# Messenger recording — increment 1 feasibility probe

Goal: confirm the CallVault-derived far-party capture (`AudioPolicy` loopback-render) actually
records the peer of a WhatsApp/Telegram call on this device (OnePlus 15, OxygenOS/Android 16, IN),
before building the full feature. Mechanism derived from CallVault (GPL-3.0); see `../LICENSE`,
`../NOTICE.md`.

## Files
- `app/src/main/java/com/davnozdu/autoresponder/msgrec/VoipAudioPolicy.kt` — far side (loopback-render).
- `app/src/main/java/com/davnozdu/autoresponder/msgrec/BypassedAudioRecord.kt` — vivo fallback.
- `app/src/main/java/com/davnozdu/autoresponder/msgrec/MsgrCaptureProbe.kt` — entry point.

R8 is off (`isMinifyEnabled = false`), so the entry point is not stripped; no keep rule needed.

## Build
Push the branch → GitHub Actions `Build APK` → download the `AutoResponder-apks` artifact →
install the release APK over the existing one (same signing key):
```
adb install -r AutoResponder-release.apk
```

## Run the probe
Recording call audio needs `CAPTURE_VOICE_COMMUNICATION_OUTPUT`, which the **shell** user
(`com.android.shell`, uid 2000) holds — so run from a plain `adb shell` (NOT root; uid 0 may fail
the permission check):
```
adb shell
CLASSPATH=$(pm path com.davnozdu.autoresponder | sed 's/package://') \
  app_process /system/bin com.davnozdu.autoresponder.msgrec.MsgrCaptureProbe 20 6
```
Args: `recSec` (default 20), `armWaitSec` (default 6), `outDir` (default
`/sdcard/Music/Recordings/Messenger`). Place the WhatsApp/Telegram call within `armWaitSec`.

If `app_process` complains about ABI, use `app_process64`.

## Read the result
The probe prints a VERDICT and writes `probe-far.wav` / `probe-near.wav`:
- `armed=false` → shell lacks `CAPTURE_VOICE_COMMUNICATION_OUTPUT`. On OPPO/OnePlus/Realme flip
  *Developer options → bottom of Apps → Disable system optimization / permission monitoring* and retry.
- far `peak` > ~200 and a plausible byte count → **the peer was captured; the mechanism works** →
  proceed to increment 2 (root daemon trigger via NotifListener, app selection UI, Messenger folder,
  journal + transcription).
- far missing/silent → loopback-render did not attach on this ROM; investigate before building more.

## Notes / gotchas (from CallVault)
- ARM the policy BEFORE the call's audio track is created (the probe arms, then waits `armWaitSec`).
- VoWiFi/VoLTE carrier Wi-Fi calling is not covered by this path.
- Some One UI builds silence the near MIC via arbitration; OnePlus (OP9/OP12) does not.
