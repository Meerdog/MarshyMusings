# Marshy's Musings — Android companion for the M5StickS3 voice recorder

Pulls the recordings off the stick over Bluetooth in the background,
transcribes them on the phone with Whisper, and hands the notes to Claude
(or anything else) through the share sheet. Nothing leaves the phone
unless you share it.

Needs firmware 2.1.0 or later on the stick (the BLE service); 2.3.0 for the battery reading.

## Install

The app was called VMM5 Notes until 0.3.0; the package id stays `com.vmm5.notes`.

Debug APK, sideloaded: copy `app-debug.apk` to the phone, open it, allow
installs from that source. Android 8+ , arm64 phones (any phone from the
last six years).

First launch:

1. Allow Bluetooth (and notifications, Android 13+).
2. Settings → download a model. **Base** is the one to start with (142 MB,
   once, Wi-Fi recommended).
3. Turn the stick on, sideways. Within a few seconds the feed says
   "Pulling 1 of N" and the notes appear, then transcribe.

## How it behaves

- A small ongoing notification ("Watching for the stick") means the
  background sync is running. It scans at low power, connects when the
  stick is in range, pulls everything, deletes each file from the stick
  once the copy on the phone has been verified, then rests a minute.
  "Sync now" (in the app or on the notification) skips the rest.
- Each note shows its tag colour (blue Work, green Run, yellow Intention),
  number, length, and the transcript once it is done. Tap for the full
  text, Play, Share text, Share audio, Retry, Delete.
- **Share new (n)** sends every not-yet-shared transcript as one text,
  with a header per note (date, tag, length, source file): paste it into
  a Claude Project chat. Notes are then marked shared.
- Settings: background sync on/off, delete-from-stick on/off, model choice
  (tiny / base / small English).

## Where things are

Notes live in the app's private storage, `files/notes/`:
`2026-10-25_0042_run-thought.wav` (the stick's file, untouched) and the
matching `.md` (header + transcript). The date is the pull date; the stick
has no clock. Models are in `files/models/`.

## Design, briefly

Modelled on the Pebble mobile app's ring handling, with the closed parts
replaced:

| Piece | Here |
|---|---|
| Foreground connected-device service with a scan → connect → pull → rest loop | `sync/SyncService.kt`, `sync/Syncer.kt` |
| BLE transfer layer (closed `haversine` in Pebble's app) | `ble/StickClient.kt`: plain Android GATT, two characteristics, the stick's `l` / `g` / `d` commands |
| `RingTransfer` state machine (Started → Saving → Completed / Failed / Discarded) | `data/Db.kt`: `transfers` keyed by the stick's file name, orphans failed before each run |
| Recording entity + entry status | `notes` table: Pending → Transcribing → Completed / Failed |
| Persistent processing queue with retries | WorkManager, one job per note, exponential backoff, 3 attempts (`work/Transcription.kt`) |
| Local transcription | whisper.cpp built into the app (`whisper.cpp/examples/whisper.android/lib`), models from the whisper.cpp release files |
| Feed with pending cards and last-synced hint; detail with player, transcript, retry, export, delete | `ui/MainActivity.kt` |

Audio: the stick's IMA ADPCM is decoded on the phone (`audio/Audio.kt`,
a port of the firmware's decoder, checked bit-exact against it in
`app/src/test`), upsampled 8 → 16 kHz for Whisper, and played back from
the 8 kHz PCM.

## Build

Android Studio, or from a shell with the SDK, NDK 27 and CMake installed:

```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

`whisper.cpp/` is a git submodule pinned to whisper.cpp v1.7.5; its
Android library module is used as is, built for arm64 only, with its native
code always compiled optimised (see the root `build.gradle.kts`). Clone with
`git clone --recursive`, or run `git submodule update --init` after a plain
clone.

### Signing

Builds are signed with the project's debug key, `app/marshy-debug.keystore`
(password `android`), so each new APK installs over the previous one. The
key is not in this public repository: anyone holding it could make an APK
that installs over yours. Keep your copy somewhere private and put it in
`app/` to build. Without it the build falls back to the computer's own debug
key; that APK works, but will not install over one signed with the project
key (uninstall first, which deletes the app's notes).

## Not yet tested on a phone

As of 2026-10-25 the APK builds and the unit tests pass, but nothing has
run on a real phone or against a real stick: BLE pull, transcription
speed, battery use of the background scan, and the share sheet are all
first-try territory. The log tag `StickClient` prints KB/s per file.

## Credits

Transcription is whisper.cpp (MIT) running OpenAI's Whisper models (MIT);
the app is built on Android Jetpack and Kotlin (Apache 2.0); the optional
tidy-up uses Google's ML Kit GenAI Prompt API with Gemini Nano (Google's
terms). The sync design is modelled on the open-source Pebble app's ring
handling. `NOTICE.md` (also in the app: Settings → Help → Credits and
licences) lists everything, including what is inside the stick's firmware.

## Installing on another phone

The app is a debug build installed by hand (not from the Play Store):

1. Send the `.apk` to the phone (email, Drive, a chat) and tap it.
2. Android asks to allow installs from that app (Files, Chrome, Gmail...):
   allow it, go back, tap Install. Play Protect may warn about an app from
   an unknown developer: "Install anyway".
3. Open the app and allow Bluetooth ("Nearby devices"), notifications and,
   if wanted, location. Set battery use to Unrestricted
   (App info → Battery) so it can sync in the background.
4. Settings → Transcription: download a Whisper model (Base, about 140 MB,
   over Wi-Fi).

Needs Android 8 or later on a 64-bit (arm64) phone, which is nearly every
phone from the last several years. The tidy-up (Gemini Nano) only works on
phones that support it, such as the Pixel 9; everything else works without
it. Each phone pairs with whatever stick it finds first, so two sticks
side by side should not be synced at the same moment. Updates install
over the old version as long as they are signed with the same key
(`app/marshy-debug.keystore`).

