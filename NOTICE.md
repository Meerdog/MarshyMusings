# Credits and licences

## Marshy's Musings

The app and the stick's firmware are by Nate Marsh, written with Claude
(Anthropic). Tango, the pixel-art cat, is Nate's own artwork of his cat.

## Built into the app

| Component | Used for | Licence |
|---|---|---|
| whisper.cpp v1.7.5 and ggml (Georgi Gerganov and the ggml authors), including its Android library module | on-device transcription | MIT |
| Whisper models (OpenAI), in ggml form from the whisper.cpp project on Hugging Face; downloaded when chosen, not shipped | the speech recognition models | MIT |
| Android Jetpack: Compose (UI, Material 3, Material icons), Activity, Lifecycle, Navigation, Core KTX, Room, WorkManager, DataStore, DocumentFile (Google / Android Open Source Project) | the app's screens, storage and background work | Apache 2.0 |
| Kotlin standard library and kotlinx.coroutines (JetBrains) | the language and its concurrency | Apache 2.0 |
| ML Kit GenAI Prompt API (Google), with Gemini Nano through Android AICore | the optional on-device tidy-up | Google's ML Kit and Gemini Nano terms (not open source) |
| JUnit | unit tests only, not shipped | Eclipse Public License 1.0 |

## The stick's firmware carried by the app

The firmware image in the app (and any firmware file loaded into it)
contains: M5VoiceRecorder by Mikhail Bogatyrev, the project it started
from (MIT); libopus (Xiph.Org and contributors, BSD 3-Clause), via
esphome/micro-opus (Apache 2.0); M5Unified and M5GFX (M5Stack, MIT),
built on LovyanGFX (lovyan03, FreeBSD), with fonts from Adafruit GFX
(BSD), TFT_eSPI (Bodmer, FreeBSD), GNU FreeFont (GPL with the font
exception) and DejaVu (Bitstream Vera licence); NimBLE-Arduino (h2zero)
and Apache Mynewt NimBLE (Apache 2.0); the Arduino core for the ESP32
(Espressif, LGPL 2.1 or later; the firmware's source is available, so it
can be rebuilt against a modified core) and ESP-IDF with LittleFS
(Espressif, ARM, Apache 2.0 / BSD 3-Clause). The firmware's own NOTICE.md
lists them in full.

## Ideas

The sync design (a foreground service that scans, connects, pulls and
rests; a transfer state machine per file; a persistent processing queue)
is modelled on the open-source Pebble mobile app's handling of the
Index 01 ring. No Pebble code is used.

The full licence texts are in each component's own repository; whisper.cpp's
is in `whisper.cpp/LICENSE` in this source tree.
