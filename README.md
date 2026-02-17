# AppCloner FOSS

A free, open-source Android app cloner with advanced identity spoofing capabilities. Supports Android 5.0+ and targets Android 14 (API 34).

## Features

- **Clone any app** – Repackage installed APKs with a new package name
- **New Identity** – 1-click randomization of:
  - Android ID & Serial
  - IMEI & IMSI
  - Wi-Fi, Bluetooth & Ethernet MAC addresses
  - Google Service Framework (GSF) ID
  - Advertising identifiers (Google, Facebook, Amazon)
  - App Set ID
  - WebView & system User-Agent
  - Build props (manufacturer, model, device, fingerprint…)
  - Battery level & temperature
  - Install/update/user-creation timestamps
- **Live identity refresh** – Regenerate identity without recloning via notification
- **Change locale** per clone
- **Hide device info** – Wi-Fi SSID, SIM/operator, DNS servers, CPU/GPU
- **Randomize build props** – Appear as a completely different device
- **Fake battery** – Random level and temperature
- **Clone management UI** – List all clones, view identity, trigger refresh
- **No root required**
- **100% FOSS** – Apache 2.0 License

## Building

### Prerequisites
- JDK 17
- Android SDK with API 34
- Gradle 8.6+ (wrapper included)

### Local Build
```bash
./gradlew assembleDebug
```

### GitHub Actions
Push to `main` – the workflow builds and uploads both Debug and Release APKs as artifacts.
For a signed release build, add the following repository secrets:
- `KEYSTORE_BASE64` – Base64-encoded keystore file
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

## Architecture

```
app/
├── cloner/        – APK repackaging, manifest & DEX patching, signing
├── identity/      – Identity generation and management
├── db/            – Room database (clone configs, identity records)
├── provider/      – Content provider for live identity delivery to clones
├── service/       – Notification service, broadcast receivers
├── ui/            – Fragments, adapters, ViewModels
└── util/          – Binary XML parser, ZIP utils, crypto helpers

assets/hooks/      – Smali source for DEX-injected identity hooks
```

## How it Works

1. User selects an installed app and configures clone options
2. The app reads the source APK from the system
3. **ManifestModifier** updates the binary AndroidManifest.xml (package name, authorities, injected components)
4. **DexPatcher** (dexlib2) scans all DEX files and redirects system API calls to hook classes
5. **HookInjector** assembles smali hook sources → DEX and merges into the APK
6. **ApkSigner** signs the resulting APK (v1 + v2)
7. Android's PackageInstaller installs the new clone
8. The **IdentityProvider** (content provider) serves fresh identity values to clones at runtime
9. **IdentityBroadcastReceiver** in each clone accepts signed identity-update intents from the main app

## License

Apache License 2.0 – see [LICENSE](LICENSE)
