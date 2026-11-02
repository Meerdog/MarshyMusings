# Marshy's Musings — project handoff

Read this first. It covers both halves of the project — the **M5StickS3
firmware** (this repo, `M5VoiceRecorder`) and the **Android companion app**
(`vmm5app`, package `com.vmm5.notes`, shown as "Marshy's Musings") — where
each stands, the decisions behind them, the problems hit along the way and
how they were solved, and what is next. `CLAUDE.md` in each repo is the
long technical reference; `README.md` is the user manual.

Owner: Nate Marsh. Windows PC, Pixel 9. Personal GitHub (with Pages) is
separate from the GitHub connected to his Claude account — never push this
project to the connected one.

Current versions (2026-10-30): **app 0.16.0** carrying **firmware 2.14.0** (one stick, one phone; see §11) for OTA (sections below were written at firmware 2.8.0 / app 0.11.0 and not updated for 0.11.1–0.11.3; see §3 item 14 for 0.11.4). Both are built and tested in the container;
2.7+ and 0.10+ have **not yet been confirmed on the hardware** (see
"Unverified" at the end). A second chat drafted parts of 2.8.0 (Minimal
theme, charging) in its own copy; this tree re-implemented them to the
same spec and is the one to build from.

---

## 1. What it is

A pocket voice recorder for capturing spoken ideas — work thoughts,
thoughts while running, daily intentions — and a phone app that pulls
the recordings over Bluetooth, transcribes them on the phone, tidies them
with an on-device model, files them into running markdown journals, and
hands them to Claude or anything else. Nothing goes to a server.

Hardware: M5Stack **StickS3** — ESP32-S3-PICO-1-N8R8 (8 MB flash, 8 MB
octal PSRAM), 1.14" 135×240 display, two buttons (KEY1 = big blue button
at the USB-C end, KEY2 = side button), ES8311 codec with a MEMS mic at the
USB-C end, BMI270 IMU, M5PM1 power chip, 250 mAh battery. No SD slot, no
RTC. Nothing pre-made adds storage; the 16-pin Hat2 bus exposes GPIOs
for a soldered microSD if ever needed.

### The stick, as the user sees it

- **Sideways** (KEY1 to the right of the screen, either way up — the IMU
  picks the orientation and the screen flips): the **Ready** screen.
  KEY1 records; KEY1 again stops. KEY2 (tap) shows **Files** (what is on
  the stick); KEY2 held two seconds opens **Settings**.
- **Upright**: the **tag picker**. KEY2 moves, KEY1 picks, KEY2 held goes
  back. Entries: Work (Project / Update / AI), Run (Place / Thought /
  Running), Intention (Morning / Midday / Evening), No tag, **Format**.
  The tag is sticky across power cycles and goes into the file name.
  KEY1 never records while upright. The recording and Saved screens turn
  upright with the stick if it is turned mid-recording.
- **Settings** (hold KEY2 on Ready): rows Theme (Tango / Plain / Wireframe
  / Warm / Pixel / Minimal), Screen (Bright / Medium / Low), Web mode,
  Back; plus version, battery mV, charge state and the last reset reason.
  KEY2 moves, KEY1 acts. Minimal is the only theme that changes layout:
  Ready is just the record dot (tag ring if armed, amber ring + "full"
  when full), Recording a pulsing stop square with the time small under
  it, Saved a tick and the length.
- **Charging** (2.8.0): plugged in, the top row shows an amber bolt +
  "charging" or green "full" instead of the percentage; "usb" in grey when
  powered but neither. The state comes from the PM1 charge flag
  cross-checked with the USB voltage, read twice before it is shown.
  Unplugging restarts the battery average; plug/unplug lights the screen.
  The 10-minute power-off still applies on USB (open decision: easy to
  flip in `shouldPowerOff`).
- **Web mode**: Wi-Fi access point `VMM5`, page at http://192.168.4.1 to
  play / download / delete. Bluetooth is off while it is on. Rarely used
  now the app exists.
- Screen dims after 1 min, off after 2; the stick powers itself off after
  10 idle minutes (one press of the power button starts it again; the
  phone cannot wake it). Bluetooth advertises whenever the stick is on,
  not recording, and not in web mode.
- Brand: Tango, Nate's late orange cat. Subtle on the stick (ears and
  whiskers on the record button, a cat in each picker icon on the cat
  themes); everywhere on the phone (his pixel-art sprite sheet).

### File names and formats

`<code>_<nnnn>.<ext>` — `run-thought_0042.opus`. The code is the tag
(`rec` for untagged, v1 `REC_0001.wav` still accepted); the number is
global, kept in NVS, only ever goes up, so sorting by number gives the
order spoken across all tags. The stick has no clock; the phone stamps
the pull time.

Formats (picker entry "Format", NVS `recorder/fmt`):
- **0 Opus 16 kHz** (default since 2.7): Ogg Opus, 20 ms frames, VBR
  ≈16 kbit/s (≈2 KB/s), voice-tuned, complexity 3. Playable by Android,
  VLC, browsers, ffmpeg.
- **1 WAV 8 kHz** / **2 WAV 16 kHz**: IMA ADPCM WAV (≈4 / 8 KB/s), the
  "plays absolutely anywhere" option.

Capacity: 4.1 MB LittleFS ≈ 35 min Opus, 17 min WAV 8k, 8.5 min WAV 16k.

---

## 2. Firmware architecture (this repo)

PlatformIO, `espressif32@6.12.0` (Arduino core 2.x; 8.x untested),
M5Unified 0.2.27, NimBLE-Arduino 1.4.3, LittleFS. Build:
`pio run -e sticks3 -t merged` → `.pio/build/sticks3/firmware-merged.bin`
(flash at 0x0) and `firmware.bin` (the app image for OTA). Native tests:
`pio test -e native` (106 tests, Unity). Nate has no PlatformIO; he
flashes the merged image with https://espressif.github.io/esptool-js/
at 0x0, then presses the power button once (the chip is left in the
bootloader after flashing — a blank screen afterwards is that, not a
bad flash).

`lib/` is hardware-free and tested: Tags, Recordings (names), Orientation,
RecorderState (the mode/picker state machine), ScreenPolicy, HighPass,
ImaAdpcm, WavHeader, BatteryFilter, BlockRing, LevelMeter, **OggOpus**
(Ogg framing), **Opus** (libopus 1.5 sources, fixed point, see §4).
`src/` is the board side: main.cpp (loop, keys, NVS, power), Renderer
(every screen, themes), AudioCapture (mic ring), RecordingWriter (WAV),
OpusWriter, Writer (interface), Storage (LittleFS, list, repair),
BlePortal (BLE service + OTA), WebPortal/WebPage (Wi-Fi page), Version.h.

Key constants: CPU 80 MHz (240 while an Opus recording runs), BLE +9 dBm,
advertising 250–500 ms, screen 100/60/30 brightness, dim 60 s, off 120 s,
power-off 600 s, IMU read every 50 ms (500 with the screen off), flash
sync every 3 s while recording.

### Partition layout (changed in 2.7.0 — one USB flash with Erase)

```
nvs      0x9000   0x5000
otadata  0xe000   0x2000
app0     0x10000  0x1E0000   (1.97 MB; firmware is 1.47 MB)
app1     0x1F0000 0x1E0000
spiffs   0x3D0000 0x420000   (LittleFS, 4.1 MB, recordings)
coredump 0x7F0000 0x10000
```

### BLE protocol (service `7a0b0001-5ec5-4a6a-9c1f-0c1d2e3f4a5b`)

- `CTL` `…0002` write: a command line. `DAT` `…0003` notify: the answer.
  `OTA` `…0004` write-without-response: firmware bytes.
- Commands: `l` list → `LIST <n> free <b> total <b>` + `<name> <bytes>`
  lines + `END`; `g <name>` get → `FILE <name> <bytes>` then raw chunks;
  `d <name>` delete → `DELETED <name>`; `x` abort; `b` → `BATT <pct> <mV>
  bat|chg|full|usb` (the fourth word since 2.8.0);
  `v` → `VER 2.7.0`; `o <size>` begin update → `OTA ready`; `op` →
  `OTA <received>`; `oe <crc32 hex>` → `OTA ok` then reboot; `ox` abort.
- File commands are refused while recording (`ERR recording`). The file
  list is cached (never re-scanned inside the BLE path — see §3).
- Only `g` and `d` count as "activity" for the idle timers; connecting
  and listing do not (see §3).

### OTA (firmware update from the phone) — how it works

1. App sends `o <size>`; stick opens the spare app slot with
   `esp_ota_begin` and allocates a 64 KB ring in PSRAM. Refused while a
   transfer is in progress.
2. App writes the image to the `OTA` characteristic in MTU-sized writes
   without response. The NimBLE host task drops bytes into the ring;
   `BlePortal::loop()` drains it with `esp_ota_write` in 4 KB blocks.
3. Every 16 KB the app asks `op` and waits until the stick's count is
   within 32 KB of what it sent (flow control; the ring never overflows).
4. App sends `oe <crc32>` (standard CRC-32 of the whole image; the stick
   uses `esp_rom_crc32_le` chained from 0, which equals zlib/Java CRC32).
   Stick checks size and CRC, `esp_ota_end`, `esp_ota_set_boot_partition`,
   answers `OTA ok`, and main.cpp restarts 1.5 s later.
5. The new image calls `esp_ota_mark_app_valid_cancel_rollback()` once
   the file system mounts; if it never gets that far the bootloader falls
   back to the previous slot.
6. A disconnect or a recording during the update aborts it
   (`esp_ota_abort`). Recording is blocked while an update is being
   written; power-off is blocked while `ble.busy()`.

The app carries the matching `firmware.bin` in `assets/` with
`firmware.txt` holding the version. Settings shows "Firmware: <stick> ·
this app carries <bundled>" and an Update button when they differ. The
very first 2.7.0 must go over USB because the partition table changed;
after that every update is OTA. **Release checklist:** bump
`src/Version.h`, `tools/manifest.json`, `tools/flash.html`, README;
build merged + app image; copy `firmware.bin` into the app's assets and
update `firmware.txt`; bump the app version; build the APK.

---

## 3. Problems hit, and what fixed them (chronological)

Worth knowing so nobody re-introduces them.

1. **Web Serial inside an artifact iframe** can't flash → use esptool-js
   directly. Blank screen after flashing = chip left in bootloader; one
   power press.
2. **Arduino core 3.x (espressif32 8.x)** broke the merged-image script
   and bloated the build → pinned 6.12.0.
3. **BLE "no answer"**: the DAT characteristic handle was read before the
   GATT server started → read lazily at send time.
4. **Lost recording #1**: `Storage::refresh()` (a directory scan) inside
   the BLE list path stalled the loop and dropped key presses → the file
   list is cached and updated incrementally (`noteAdded`/`noteRemoved`).
   Rule: nothing in `loop()` while Ready may take more than a few ms.
5. **Lost recording #2 (the big one)**: recordings "vanished" after the
   phone connected. Red herrings chased: resets (added the reset-reason
   readout — it said "power on"), connects mid-recording (BLE is now
   off while recording), flash sync interval (10 s → 3 s). Real cause:
   the **app deleted stick files it thought it already had**. It matched
   on file name only; after a flash erase the stick's NVS counter
   restarted, new files reused old names, and the app "tidied" them off
   the stick without pulling. Fix (app 0.7.2): a file is "already have
   it" only if name *and* size match and the copy is really on disk;
   otherwise it is pulled under a de-duplicated name. Rule: the app
   never deletes from the stick what it does not hold.
6. **KEY2 loop in web mode**: KEY2 acted on the press in web mode and on
   the release elsewhere, so one press turned web mode off *and* back on
   → KEY2 acts on release everywhere except while recording.
7. **Stick never powered off**: every BLE connection counted as activity
   and the phone reconnects every 20 s during its window → only `g`/`d`
   are activity. This was also why the screen kept lighting up.
8. **Bluetooth only worked on USB, not battery**: the radio was at +3 dBm
   to save power and the USB cable was acting as an antenna → full power
   (+9 dBm), and the BT mark on screen now reflects the radio's real state
   (`isAdvertising()`), with a once-a-second restart if it drops.
9. **Both buttons at once as a gesture**: rejected — KEY1 starts
   recording on the press, and waiting to see if KEY2 follows would delay
   every recording. Settings is a 2 s KEY2 hold instead.
10. **Room DB migration**: v2 would have wiped notes (destructive
    migration) → explicit migrations 1→2→3→4→5 ever since.
11. **Gradle through the proxy** returns 429 → `--max-workers=1`,
    `org.gradle.parallel=false`, retry loop. The ML Kit GenAI library is
    compiled with a newer Kotlin than the project; the app passes
    `-Xskip-metadata-version-check`.
12. **libopus as a PlatformIO library**: esphome/micro-opus is an ESP-IDF
    CMake component, so its bundled libopus sources were copied into
    `lib/Opus` with a `library.json` + `opus_build.py` (fixed point, float
    API off, `NONTHREADSAFE_PSEUDOSTACK` with a 120 KB pseudostack that
    libopus mallocs on first use → PSRAM, because the Arduino task stack
    is 8 KB). Adds ≈140 KB of flash. Verified on the host: the muxer +
    encoder output is read by ffmpeg (right duration and level; a file
    truncated mid-way still plays to its last page).
13. **Lost recording hardening in general**: writer syncs every 3 s; WAV
    headers are repaired at boot from the file length; Ogg needs no
    repair (pages are self-describing; files under 200 bytes are
    removed); a reset reason is shown on Settings.
14. **Transcription far slower than projected (0.11.3 and earlier)**: the
    debug APK built whisper.cpp's native code at `-O0` — whisper's
    CMakeLists only adds `-O3` when `CMAKE_BUILD_TYPE` is not Debug, and a
    debug APK passes Debug. Measured on the same whisper.cpp + base.en on a
    desktop: 17.5 s vs 2.2 s for an 11 s clip (≈8×). Fix (0.11.4): the root
    `build.gradle.kts` forces `-DCMAKE_BUILD_TYPE=Release` (plus NDK 27.2,
    arm64 only) on the `:whisper` module through `finalizeDsl`, so whisper.cpp
    stays unmodified. Check after any build: `ggml_vec_dot_f16` in
    `libwhisper_v8fp16_va.so` should have no `[sp` loads and several `fmla`.
    The Activity log's "Transcribed" line now shows the time as a multiple of
    the audio length and the thread count.
15. **Debug signing key changes between workspaces**: each container makes
    its own `~/.android/debug.keystore`, so an APK built in a new chat would
    not install over the last one (Android refuses; uninstalling wipes notes,
    audio and models). From 0.11.4 the project carries its own key,
    `app/marshy-debug.keystore` (password `android`, alias
    `androiddebugkey`, SHA-256 8B:74:C1:7D…6C:79 — the key 0.11.3 and
    earlier were signed with), wired into the debug signing config. Keep it
    out of the public repository (see README, Signing); every build signed with it installs over the previous one.

---

## 4. The Android app (`vmm5app`)

Kotlin, Jetpack Compose Material 3, Room (notes / transfers / events,
schema v5), WorkManager, DataStore, FileProvider. Modelled on the Pebble
app's structure: a foreground service with a scan → connect → pull → rest
loop and a persistent transfer state machine. Build:
`./gradlew --no-daemon -q --max-workers=1 :app:assembleDebug` with the
SDK in `scratchpad/android-sdk`, NDK 27.2.12479018 for the `:whisper`
module (whisper.cpp v1.7.5, arm64). Debug APKs are installed by hand.

Modules and files worth knowing:
- `ble/StickClient.kt` — scan by service UUID, GATT, the protocol above,
  `version()`, `update()` (OTA client).
- `sync/SyncService.kt` — the foreground loop and the 10-minute window;
  `sync/StickWatcher.kt` — the OS-level filtered scan (pending-intent
  scan on the service UUID) that wakes the app when the stick appears,
  paused while the service runs, re-armed when it stops, on boot and when
  Bluetooth comes back; `sync/Syncer.kt` — one pull (list, get, verify
  size + read-back, save, then delete from the stick), version and
  battery query, location stamp; `sync/Updater.kt` — the OTA driver;
  `sync/Where.kt` — last-known location + geocoder.
- `audio/Audio.kt` — IMA ADPCM decoder (bit-exact with the firmware),
  Ogg Opus via MediaExtractor/MediaCodec (48 kHz out), `StickAudio.load`
  for either, Whisper input conversion, Room/Chorus playback effects,
  Player.
- `work/Transcription.kt` — WorkManager job per note, whisper.cpp,
  timing, restart; `work/Cleanup.kt` — the `Cleaner` engine interface,
  `NanoCleaner` (Gemini Nano through ML Kit GenAI Prompt API,
  `com.google.mlkit:genai-prompt:1.0.0-beta4`), `CleanupWorker`.
- `data/Journal.kt` — `musings.md` plus `musings-work/run/intention/
  untagged.md`, appended per transcript under day headings; share, save
  a copy, mirror into a user-picked folder (SAF tree URI — a Google
  Drive folder works, Drive syncs it). `data/ListCapture.kt` (0.11) —
  the code phrase ("add to list", editable) matched loosely on the raw
  Whisper text; the rest of the sentence becomes `- [ ] item` in
  `musings-list.md`, mirrored with the journals. Tidy-up does not touch
  list items (optional Nano tidy of items is a possible follow-up).
- `audio/PhoneRecorder.kt` + `ui/RecordScreen.kt` + `ui/RecordWidget.kt`
  (0.11) — record on the phone: MediaRecorder Ogg Opus 16 kHz (AAC .m4a
  before Android 10), tag chips, big button; the note gets `P<n>` numbers
  (own counter), device name `phone-<nnnn>.opus`, real time and place,
  and goes down the same transcribe / tidy / journal / list path. A
  home-screen widget and a Quick Settings tile open the app straight into
  recording (`com.vmm5.notes.RECORD`).
- `ui/` — NotesScreen (grouped by day, newest first, selection, FAB
  "Send N new to Claude"), DetailScreen (play + effects, Tidied / As
  spoken, rename, restart transcription, tidy again, location link,
  delete), ActivityScreen (event log), SettingsScreen (everything
  below), Share.kt (bundle with per-note headers; Claude package
  `com.anthropic.claude`).

Settings, top to bottom: Stick (battery with charging state — the
percentage is frozen at its last unplugged value while plugged in;
firmware + Update; background sync; keep looking all day; delete after
pulling) · Journal (files, share, save, folder mirror) · List (code
phrase, on/off, share) · Where (location on each pulled note; needs
"Allow all the time") · Tidy up (Gemini Nano status/download, enable,
use tidied text in journals, **editable prompt** with reset) ·
Transcription (Whisper tiny/base/small.en downloads) · How it works.

Behaviour notes: notes carry the pull time, not the speaking time; the
location is where the phone was at pull time; the raw transcript is
always kept and shown alongside the tidied one; journals wait for the
tidy-up when it is on, so each entry is written once; "Restart
transcription" replaces the queued job (a running whisper pass can't be
interrupted mid-call, the new job runs after it).

Phone-side settings that matter: battery use **Unrestricted** for the
app (otherwise Android stops delivering the wake-ups); Gemini Nano is
fetched by the **Android AICore** system app on its own schedule (Wi-Fi,
often while idle) — the app only asks for it; if it never arrives,
update AICore in Play Store and restart the phone.

---

## 5. Decisions and their reasons

- **Opus by default, WAV optional.** WAV/ADPCM was chosen first because
  files had to be double-clickable on a PC and the codec is nearly free
  on the chip. Opus is 2–4× smaller at better quality and is Whisper's
  native 16 kHz, so it became the default once the OTA layout made
  flash space matter. WAV stays for "share anywhere".
- **Pull time, not speaking time.** No RTC on the stick; the number
  preserves order within a day.
- **Phone does the thinking.** Transcription (whisper.cpp) and tidy-up
  (Gemini Nano) run on the phone; the stick only records and serves
  files. Nothing leaves the phone unless shared.
- **Wake on advertisement instead of scanning all day.** The phone's
  radio holds a filter for the stick's UUID; the app wakes when the
  stick appears, runs its 10-minute window, goes quiet. The stick goes
  silent while recording and reappears after, which doubles as a fresh
  wake after every recording.
- **The app never deletes what it does not hold** (problem 5 above).
- **Only transfers count as activity on the stick** (problem 7).
- **Settings behind a 2 s hold; web mode tucked into Settings.** Few
  presses for the common things (record, files, tag), more for the rare.
- **Themes are colour maps**, not separate drawings: every colour goes
  through `Renderer::themed()`, so a theme is ~15 lines.

---

## 6. Where to go next (asked for or discussed)

Open decisions from the 2.8.0 / 0.11.0 round (defaults chosen, easy to
change): power-off still after 10 idle minutes on USB; list phrase "add
to list" with the item running to the end of the sentence; phone notes
numbered P1, P2… separately from the stick; the phone records Opus 16 kHz
at 16 kbit/s like the stick.

1. **Confirm 2.7.0 + 0.10.1 on the hardware** (see §7). First real Opus
   recording: check the Saved screen's length, playback in the app, and
   the serial stats line `encode max N ms` (if it approaches 60, lower
   `OpusWriter::kComplexity`).
2. **First OTA**: flash 2.7.0 by USB with Erase, then any later version
   from Settings → Firmware → Update. Watch Activity for the log lines.
3. **Sleep-when-idle ("ring mode")**: deep sleep after ~2 min idle, KEY1
   wakes (half a second to boot), stay awake advertising ~5 min after a
   recording so the phone pulls. Would turn a few hours of battery into
   days. Discussed, not built.
4. **Second tidy-up engine**: llama.cpp with downloadable GGUF models
   (Gemma 3 1B / Qwen 2.5 1.5B), behind the existing `Cleaner`
   interface, the way Whisper models download. Native build is the cost.
5. **Journal backfill** for notes transcribed before 0.7.
6. **Export audio with the Room/Chorus effect applied** (playback only now).
7. **Web/USB page and `pull.py` with Opus**: both accept `.opus` already;
   the page plays it via the browser's Ogg Opus support.
8. Stick storage: no pre-made add-on; a better codec was the lever and is
   done. A soldered microSD on the Hat2 bus is the only hardware route.

---

## 7. Unverified on the hardware (as of this handoff)

- Firmware 2.8.0: the Minimal theme on the panel, the charge detection
  (PM1 flag + VBUS threshold of 4.0 V — if "charging" never shows, check
  the serial `batt` line for the raw vbus and flag values).
- App 0.11.0: MediaRecorder Opus on the Pixel, the widget and tile, the
  list capture on real Whisper output (punctuation varies).
- Firmware 2.7.0 entirely: Opus encode timing at 240 MHz, the mic
  restart between 8 and 16 kHz, Ogg files on the real flash, the new
  partition layout (first boot formats LittleFS), OTA end to end, the
  `kShortAxisSign` for the landscape flip (set +1, never confirmed).
- App 0.9–0.10: location permission flow, Gemini Nano download/availability
  on the Pixel 9, Opus decoding through MediaCodec on the phone, the
  Updater.
- Earlier and confirmed by Nate: BLE sync, tags and picker, Tango theme,
  Settings screen, journals, wake-on-stick (works), power-off fix (2.6.2).

## 8. Deliverables and where they are

- `dist/M5VoiceRecorder-v2.8.0.bin` (merged; only needed over USB when
  coming from 2.6 or older, with Erase) and `dist/M5VoiceRecorder-v2.8.0-app.bin`
  (the OTA image, bundled in app 0.11.0 — from 2.7.0 on, update from
  Settings → Firmware).
- `tools/flash.html` + `manifest.json` (ESP Web Tools page for GitHub
  Pages), `tools/pull.py` (USB pull on a PC), `web/index.html` (Web
  Bluetooth pull page), `tools/serial_cmd.py` (serial hooks, screenshots).
- App: `MarshysMusings-0.11.0-debug.apk`, source zip of `vmm5app`.
- A simulation page of the stick and the app (Claude artifact
  "Marshy's Musings") with a revisions tab.

## 9. App 0.14.0: syncing only when there is something, fewer notifications, stick settings

- Firmware 2.12+ advertises a "new notes" bit (manufacturer data
  `FF FF 'M' '5' <flags>`). Once a sync has seen 2.12+, the app stores
  `watch/flag` (SharedPreferences) and the standing scan (`StickWatcher`)
  adds `setManufacturerData(0xFFFF, "M5"+0x01, mask FF FF 01)` to its
  filter: the radio wakes the app only when the stick has something to
  pull. Before that (older firmware) it wakes on any advertisement.
- Woken that way the service starts with `ACTION_AUTO`: one pull, then
  `stopSelf()` (no ten-minute window, no lingering status). Three failed
  tries fall back to the usual window. Opening the app or Sync now keeps
  the window as before.
- After a pull the app sends `m <highest number on the stick>`; the stick
  clears its bit even when "delete after pulling" is off.
- `sync/Notifier.kt` owns the notifications: one "Synced N notes"
  (id 2) per run of syncs within 30 minutes, sounding at most
  `notifyMaxPerHour` times an hour (default 3; then silent updates);
  low battery only if switched on (default off); the "nearby" nudge is
  gone, replaced by "The stick has new notes" only when Android refuses a
  background start. The working status (sync, transcription) goes to an
  IMPORTANCE_MIN channel unless "Minimise the working status" is off.
- Settings → Stick settings (firmware 2.12+): theme, screen brightness,
  recording format. Choices are queued in prefs (`want_*`), sent with
  `s key value` on the next connection (Send now starts one), then read
  back with `c` and shown; "(to send)" marks a queued choice.

## 10. App 0.15.0: Minimal look, diagnostic log, firmware files, credits

- Settings → Look: Tango (default) or Minimal (`app_style` = 1): a neutral
  grey colour scheme with a slate accent and no cat. `LocalMinimal`
  (Theme.kt) is read by `TangoImage`, which draws nothing in Minimal, and
  by the few places that mention Tango in words.
- Diagnostic log (`data/Diagnostics.kt`): Activity → Export log, or
  Settings → Help. A text file in `cache/share/` with app/phone versions,
  firmware carried and loaded, every setting (`Prefs.dump()`), notes (one
  line each: status, timings, errors; no transcripts, no locations),
  transfers, the last 2000 activity events and the app's own logcat.
  Shared with the share sheet.
- Firmware files (`sync/FirmwareFile.kt`): Settings → Firmware files →
  Load firmware file. Accepts the `-app.bin` or the merged USB image (cut
  at 0x10000, the partition table's AA 50 at 0x8000 identifies it);
  requires the 0xE9 image magic, a size within the 1.97 MB slot and the
  marker `M5VR-FW-VERSION=<v>` (firmware 2.13.0+). Kept in
  `files/firmware/loaded.bin`. `Updater` sends the newer of the bundled
  and the loaded image (`targetVersion`). The stick verifies the CRC and
  rolls back if the new image does not start. Tests: `FirmwareFileTest`.
- Credits: `NOTICE.md` (repo root and `assets/`), shown in Settings →
  Help → Credits and licences. The firmware has its own `NOTICE.md`. The
  app itself has no licence file yet: Nate's call.
- Installing on another phone: see README "Installing on another phone".

## 11. App 0.16.0: one stick, one phone (firmware 2.14+)

- `sync/Pairing.kt` (SharedPreferences "pairing"): a random 64-bit phone
  id; the paired stick id; an "approved" stick (the user said yes, claim
  it on the next connection); ignored sticks ("Not mine").
- `Pairing.accepts()` decides which advertisements the app connects to
  (sync and updates): its own stick, an approved one, or, with none
  paired, a stick with no phone that is not ignored. Sticks before 2.14
  (no id) only while nothing is paired, so the upgrade path still syncs.
- `Pairing.handshake()` right after reading the version: `h <phone>`
  (`claim` if approved). ok → paired (logged); new → ask the user
  ("Found a stick nearby. Use it with this phone?": a dialog in the app,
  plus a notification, id 5) and stop; other → not ours (unpair if it was);
  denied → logged. A claim on another phone's stick waits up to 35 s for
  KEY1 on the stick.
- Upgrade: 0.16 syncs a 2.13 stick as before, updates it to 2.14; the next
  sync meets an unowned stick and asks once.
- `StickWatcher` with 2.14: filter `M5 01 <id>` (mask `FF FF 01 FF FF`) for
  the paired stick, or `M5 00` (mask `FF FF 02`, a stick with no phone)
  when none is paired. `watch/ids` is set by the sync from the version.
- Settings → Stick: "Paired stick: XXXX", Change (a picker listing nearby
  sticks with their state; picking one approves it), Unpair.
- `StickClient.scan()` takes an accept filter and returns `StickAd`
  (device, id, hasNew, owned, rssi); `scanAll()` for the picker;
  `hello()`.

