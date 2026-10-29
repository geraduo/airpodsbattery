<div align="center">

**English** | [Русский](README.ru.md)

# AirPods Battery

**AirPods battery level on Android: left earbud, right earbud and case.**  
No account, no cloud, no ads, no extra dependencies.

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white)
![Dependencies](https://img.shields.io/badge/dependencies-0-lightgrey)
![License](https://img.shields.io/badge/license-MIT-blue)

</div>

---

## About

Android has no built-in way to see the battery level of each AirPods earbud separately. This app reads the data
the earbuds broadcast over Bluetooth and shows it on a single screen.

Everything runs locally: the app only needs Bluetooth and never uses the internet.

## Features

- Battery level of the left earbud, right earbud and charging case in 10 % steps
- Charging indicator for each device
- Model detection (AirPods, AirPods 2, AirPods 3, AirPods Pro, AirPods Pro 2)
- Overall battery level of connected earbuds, when the system passes it to the app
- Built-in diagnostics: shows which packets the phone is receiving
- Two interface languages: English (default) and Russian, switched with the **EN / RU** toggle in the top bar; the choice is remembered
- No third-party libraries: plain Android SDK, the UI is built in code

## How it works

Data comes from two sources.

| Source | What it shows | When it is available |
| --- | --- | --- |
| Apple Bluetooth LE advertisement (type `0x07`) | Left, right, case, charging flags | The case lid is open near the phone |
| Headset command `+IPHONEACCEV` | A single overall percentage | Earbuds are connected; the value arrives on connect and when the level changes |

Advertisement packet layout (manufacturer data, Apple company ID `0x004C`):

| Byte | Meaning |
| --- | --- |
| 0 | Message type (`0x07`) |
| 1 | Data length |
| 3–4 | Model code |
| 5 | Status (including the "earbuds swapped" flag) |
| 6 | Earbud battery, one nibble each |
| 7 | High nibble: charging flags; low nibble: case battery |

Battery values from 0 to 10 map to 0–100 %; the value 15 means "no data".

## Supported models

| Model | Code |
| --- | --- |
| AirPods (1st generation) | `0x0220` |
| AirPods (2nd generation) | `0x0F20` |
| AirPods (3rd generation) | `0x1320` |
| AirPods Pro | `0x0E20` |
| AirPods Pro (2nd generation) | `0x1420` |

> Tested on AirPods 2. Other models are parsed according to the packet format description and have not been tested separately.

## Requirements

- Android 8.0 (API 26) or newer
- Bluetooth LE
- Location turned on: without it Android does not deliver Bluetooth scan results

### Permissions

| Permission | Why it is needed |
| --- | --- |
| `BLUETOOTH_SCAN` (Android 12+) | Scanning for AirPods advertisement packets |
| `BLUETOOTH_CONNECT` (Android 12+) | Receiving data from a connected headset |
| `ACCESS_FINE_LOCATION` | Android requires it for Bluetooth scanning. Your location is not used or sent anywhere |

## Build

### With GitHub Actions (no Android Studio needed)

1. Upload all project files to a GitHub repository, including the hidden `.github` folder.
2. Open the **Actions** tab and select the **Build APK** workflow. It runs on every commit and can also be started manually with **Run workflow**.
3. When it finishes with a green check, download the `AirPodsBattery-apk` artifact. It contains `app-debug.apk`.
4. Copy the APK to your phone and install it, allowing installation from unknown sources.

### With Android Studio

1. Open the project folder in Android Studio (Hedgehog or newer) and wait for Gradle sync to finish.
2. Connect a phone with USB debugging enabled and press **Run**.

### From the command line

You need JDK 17, the Android SDK and Gradle 8.9 or newer:

```bash
gradle assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Usage

1. Launch the app and grant the permissions.
2. Open the case lid near the phone. Values appear within a couple of seconds.
3. If the earbuds are already connected, the "Overall battery via connection" line updates when they send data. To speed this up, reconnect the earbuds while the app is open.

## Troubleshooting

Press the **Diagnostics** button on the main screen. The line it shows tells you how many packets the phone has received:

| What you see | What it means |
| --- | --- |
| Packets `0` | Missing permissions, or Bluetooth or location is off |
| Packets received, `Apple 0` | The phone sees other devices, but no Apple products |
| `Apple` present, but `AirPods(07) 0` | The earbuds are not broadcasting the needed data: open the case lid and disconnect the earbuds from other devices |
| `AirPods(07)` grows, but no values shown | Open an issue and include the diagnostics line |

## Project structure

```
.
├── .github/workflows/build.yml      APK build in GitHub Actions
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/app/podsbattery/
│       │   ├── MainActivity.kt      scanning, packet parsing, UI
│       │   └── Widgets.kt           palette and battery indicator
│       └── res/                     theme, colors, icon, strings (en, ru)
├── build.gradle.kts
└── settings.gradle.kts
```

## Limitations

- Exact per-earbud values arrive only while the earbuds are broadcasting (usually when the case lid is open).
- Apple's packet format is undocumented and may change with earbud firmware updates.
- Battery accuracy is limited to 10 % steps, because that is how the earbuds report it.

## Authors

- [@geraduo](https://github.com/geraduo): idea, testing, design
- @claude: app code and documentation

## Acknowledgements

The advertisement packet format was worked out by the community, including the
[OpenPods](https://github.com/adolfintel/OpenPods) project.

## Disclaimer

This project is independent and is not affiliated with Apple Inc. AirPods is a trademark of Apple Inc.

## License

[MIT](LICENSE)
