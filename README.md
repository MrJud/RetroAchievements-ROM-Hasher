# RetroAchievements Pegasus Frontend

A companion app for [Pegasus Frontend](https://pegasus-frontend.org/) that scans ROM folders, hashes files using [rcheevos](https://github.com/RetroAchievements/rcheevos), looks up games on the RetroAchievements API, and writes a JSON cache file that compatible themes can read to display achievements, badges, and game metadata.

## Platform Support

| Platform | Status |
|----------|--------|
| **Android** | ✅ Available |
| **Windows** | 🚧 In progress |
| **Linux** | 🚧 In progress |

## Compatible Themes

| Theme | Platform | Link |
|-------|----------|------|
| **ReStory** | Pegasus Frontend | [GitHub](https://github.com/MrJud/ReStory) |

> Want to add support to your theme? See the [Integration](#integration-for-theme-developers) section below.

## Features

- **Accurate hashing** via rcheevos native library (NDK) — same algorithm as RALibretro/RAHasher
- **Parallel API lookups** — 8 concurrent requests with retry & backoff
- **Incremental** — skips already-cached games on subsequent runs
- **Background scan** — runs as a ForegroundService with progress notification
- **No UI** — transparent activity, launches and finishes immediately
- **Intent-based** — any app/theme can trigger a scan by sending an Intent

## Install

Download `ra-rom-hasher.apk` from [Releases](https://github.com/MrJud/RetroAchievements-ROM-Hasher/releases/latest) and install on your Android device.

On first launch, grant "All Files Access" when prompted (required to read ROMs on SD card).

## Integration (for theme developers)

### Launch via Intent

```
intent://scan#Intent;
  package=com.ra.romhasher;
  action=com.ra.romhasher.SCAN;
  S.ra_user=YOUR_USERNAME;
  S.ra_api_key=YOUR_API_KEY;
end
```

**Optional extras:**
| Extra | Type | Description |
|-------|------|-------------|
| `ra_user` | String | RetroAchievements username (required) |
| `ra_api_key` | String | RetroAchievements web API key (required) |
| `rom_dirs` | String[] | Directories to scan (optional, reads `game_dirs.txt` if omitted) |
| `cache_path` | String | Output path (default: `/sdcard/your_theme/ra_hashes_cache.json`) |

### QML example (Pegasus Frontend)

```qml
function launchHasher() {
    var uri = "intent://scan#Intent"
        + ";package=com.ra.romhasher"
        + ";action=com.ra.romhasher.SCAN"
        + ";S.ra_user=" + raUser
        + ";S.ra_api_key=" + raApiKey
        + ";end";
    var ok = Qt.openUrlExternally(uri);
    if (!ok) {
        // APK not installed — open download page
        Qt.openUrlExternally("https://github.com/MrJud/RetroAchievements-ROM-Hasher/releases/latest/download/ra-rom-hasher.apk");
    }
}
```

### Read the cache

The app writes a JSON file (default `/sdcard/your_theme/ra_hashes_cache.json`):

```json
{
  "external_hashes": {
    "supermarioworld|snes": {
      "gameId": 228,
      "title": "Super Mario World",
      "consoleName": "SNES/Super Famicom",
      "imageIcon": "/Images/068013.png",
      "numAchievements": 108
    }
  },
  "verify_map": {
    "supermarioworld|snes": "A31BEAD4..."
  }
}
```

**Key format:** `cleanFileName|platform` where:
- `cleanFileName` = filename without extension, parentheses/brackets removed, lowercased, non-alphanumeric stripped
- `platform` = parent folder name, lowercased, non-alphanumeric stripped

### Standalone config (without Intent)

Create `/sdcard/ReStory/hasher_config.json`:

```json
{
  "ra_user": "YourUsername",
  "ra_api_key": "YourApiKey",
  "rom_dirs": ["/storage/3030-3363/Emulation/Roms"],
  "cache_path": "/sdcard/ReStory/ra_hashes_cache.json"
}
```

Then tap the app icon to start a scan.

## Build from source

```bash
git clone --recurse-submodules https://github.com/MrJud/RetroAchievements-ROM-Hasher.git
cd RetroAchievements-ROM-Hasher
./gradlew assembleDebug
```

Requires: Android SDK, NDK (installed automatically by Gradle), JDK 17.

## Supported ROM extensions

`bin iso gba gbc gb nes sfc smc md gen smd n64 z64 v64 nds 3ds psp a26 a78 lnx pce sgx ws wsc 32x gg sms sg col ngp ngc vb fig swc zip 7z chd cso pbp cue`

## License

MIT
