# 📩 SMS Forwarder

> A personal Android app: catches incoming SMS on a dual-SIM phone and forwards
> to email only the ones that arrived on the chosen SIM.

<p>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white">
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-24-blue">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white">
  <img alt="UI" src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4">
</p>

You pick the target SIM once in the app; it is remembered by its physical slot,
so matching does not depend on VoLTE/IMS or on whether the carrier wrote the
number onto the SIM. Sending happens in the background over SMTP (Gmail) with
retries. No analytics and no third-party SDKs — just Compose, WorkManager and
JavaMail. Secrets are kept out of the repository.

---

## ✨ Features

- 📥 Catches incoming SMS via a `BroadcastReceiver`.
- 🎯 Forwards only SMS from the target SIM (pick it once, matched by slot).
- 📧 Sends email over SMTP in the background, with retries on network failures.
- 🔒 Never stores SMS and keeps no secrets in code or repository.
- 🖥 A single Compose screen: status, permissions, SIM list, test, battery.
- 🌍 UI in English and Russian (follows the system language).

## 🧩 How it works

| Component | Role |
|---|---|
| `SmsReceiver` | Catches `SMS_RECEIVED`, joins multipart text, resolves `subId` (which SIM received it). If it matches the target SIM — enqueues a WorkManager job. Does not store the SMS. |
| `ForwardWorker` | `CoroutineWorker`: builds the email (subject "SMS from \<sender\>", body: sender + time + text) and sends it over SMTP. Network error → `retry()`, auth error → `failure()`. |
| `SimHelper` | Maps the user-chosen slot to the active SIM's `subId` via `SubscriptionManager` and returns the SIM list for the screen. Resolves the number for display only (carrier → UICC → IMS). Requires `READ_PHONE_STATE` (+ `READ_PHONE_NUMBERS` for the number). |
| `MainActivity` | The single Compose screen: status, permissions, info, SIM list, "Send test" and "Disable battery optimization" buttons. |
| `Config` | Reads values from `BuildConfig`. |

## 🔐 Permissions

| Permission | Why | Type |
|---|---|---|
| `RECEIVE_SMS` | Catch incoming messages | runtime |
| `READ_PHONE_STATE` | Read the SIM list (slot, carrier) | runtime |
| `READ_PHONE_NUMBERS` | Show each SIM's number (display only) | runtime |
| `INTERNET` | Send the email | normal |

Both runtime permissions are requested from the screen with the "Grant
permissions" button.

## ⚙️ Configuring secrets

Secrets do **not** go into Git — they live in `local.properties` and are
injected into `BuildConfig` at build time (see `app/build.gradle.kts`).

1. Copy the template:
   ```bash
   cp local.properties.example local.properties
   ```
2. Fill in your values in `local.properties`:
   ```properties
   forwarder.smtpUser=your.sender@gmail.com
   forwarder.smtpPassword=xxxxxxxxxxxxxxxx   # see "app password" below
   forwarder.forwardTo=your.inbox@example.com
   ```
   The target SIM is chosen in the app (by slot), not here.

> **⚠️ Gmail app password.** Your regular password won't work for SMTP. You need
> an "App Password": Google Account → *Security* → *2-Step Verification* →
> *App passwords*. It's 16 characters with no spaces.

> Values are baked into the APK **at build time**. If you change
> `local.properties`, rebuild. The strings are visible in the built APK (this is
> not encryption, just a way to keep secrets out of the repository).

## 🛠 Build and install

```bash
# Debug build
./gradlew :app:assembleDebug

# Install on a connected phone
./gradlew :app:installDebug

# Release APK → app/build/outputs/apk/release/
./gradlew :app:assembleRelease
```

## ✅ Testing on a phone

1. Install the app, open the screen, grant the permissions.
2. In the SIM list, tap the SIM you want to forward — it gets marked
   "forwarded ✓". If no SIM is selected, a yellow warning shows and **all** SMS
   are forwarded.
3. Tap "Send test" — an email should arrive.
   *(The button is enabled only when SMTP and the recipient are configured.)*
4. Send an SMS to the target SIM → it should reach your email; to the other SIM
   → it should not.
5. Tap "Disable battery optimization" so the app doesn't "fall asleep"
   (especially on Xiaomi/Samsung).

### 🩺 If the SIM filter doesn't work

The filter relies on the incoming SMS carrying a `subId`. Resolving it from the
intent is flaky on some firmwares. `SmsReceiver` logs all extras of the incoming
SMS (tag `SmsReceiver` in `logcat`):

```bash
adb logcat -s SmsReceiver
```

Send test SMS to both SIMs and check which key carries `subId`. The supported
keys are listed in `SmsReceiver.extractSubId`.

> **Fallback:** if no SIM is selected, or the incoming `subId` never arrives,
> **all** incoming SMS are forwarded — the screen shows a yellow warning.

## 📦 Dependencies

Minimal: Jetpack Compose (single screen), WorkManager (background sending),
`com.sun.mail:android-mail` (SMTP). No analytics, no third-party SDKs.

## 📄 License

MIT — see [LICENSE](LICENSE).
