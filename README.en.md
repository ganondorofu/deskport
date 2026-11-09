# Deskport

[日本語](README.md) | English

A remote desktop that lets you view and control your PC from a phone or tablet with low latency.
It started as a project tailored to the Galaxy Z Fold 8 Ultra (foldable), but is not limited to any specific device.

## Features

- **Custom streaming:** Screen capture (DXGI), GPU HEVC encoding (NVENC), sent over UDP. Supports 60 fps and above.
- **Encryption:** Video, audio, cursor and input packets are all encrypted with AES-256-GCM.
- **Multiple displays:** View one display, or all of them side by side. Display resolution is handled automatically.
- **Pair with a QR code:** Just scan the QR code shown on the PC with your phone. No key typing needed.
- **LAN or VPN:** Register several addresses for one PC; the fastest one is chosen automatically when you connect. Any kind of VPN works.
- **Audio:** The sound playing on your PC plays on your phone. Your PC's volume and settings are not changed. Adjust the volume on the phone.
- **File transfer and clipboard:** Send files between phone and PC and sync the text clipboard. Files received on the PC go to `Downloads\Deskport`.
- **Controls:** Touch, trackpad, scroll wheel, keyboard (compact, tenkeyless, full size; US/JIS), custom shortcuts, lockable modifier keys (for Alt+Tab and the like), press-and-hold drag, cursor follow and inertia.
- **Combined keyboard:** Use the keyboard itself as a trackpad (swipe to move the cursor, two fingers to scroll).
- **Foldable support:** Layout and settings are remembered per posture (Straight, Flex, Expanded). Can be turned off on non-foldable devices.
- **Connection password:** Keep other people who open the app on your phone from connecting to your PC, with a 4-digit number or any string (password manager autofill supported).
- **Camera (Windows 11):** Use your phone's camera as a PC camera in Teams, Zoom, browsers and more.
- **Lock screen and UAC (optional):** Also control the lock screen, UAC prompts and Ctrl+Alt+Del.
- **Language:** Japanese and English (follows the device language; can be changed in Settings → Other).
- **Auto update:** Verifies signed update info, and updates both the PC host and the app.

## Requirements

| | |
|---|---|
| Host (PC) | Windows 10/11, NVIDIA GPU (NVENC), WebView2 (included in Windows 11). Camera requires Windows 11 (build 22000 or later) |
| Device | Android 14 or later |

GPUs other than NVIDIA are not supported yet.

## Usage

1. Get `deskport-host.exe` and `deskport.apk` from [Releases](https://github.com/ganondorofu/deskport/releases).
2. Run `deskport-host.exe` on the PC and press “Install” (an administrator prompt appears once, for the firewall settings).
3. Install `deskport.apk` on your phone.
4. In the app's host screen, press “Register with QR” and scan the QR code in the “Register” tab of the PC window.
5. Tap the registered host to connect.

The QR code contains the key. Do not show it to others. You can regenerate the key from the “Register” tab (already registered devices stop working).

### Camera (use your phone camera as a PC camera)

1. In the PC window: Settings → Camera → Enable (after a confirmation, an administrator prompt appears once).
2. “Deskport Camera” appears in the camera list of Teams and similar apps. When no video is coming in, it shows a gray screen.
3. On the phone, connect to the PC and choose “⋮” (top right) → “Send camera to PC”. The video shows on the PC camera (about 15 fps, about 1280x720).

- While the camera is enabled and the host is running, the virtual camera stays visible in Windows. Choosing “Disable”, or quitting the host, removes it.
- Sending only works while the phone app is on screen (Android stops the camera in the background).
- The virtual camera DLL is placed in `C:\Program Files\DeskportVCam` and registered (HKLM). Disabling removes the registration.

### Lock screen and UAC (optional)

In the PC window: Settings → Lock screen & UAC support → Enable (after a confirmation, an administrator prompt appears once). No setting is needed on the phone. When enabled, the phone connects to a helper process (UDP 49001).

- This installs one Windows service (SYSTEM privileges). **While it is enabled, other programs running on this PC can also read the key (`secret.txt`) and operate UAC prompts and the like.** Press “Disable” when you are not using it (this removes the service, files and firewall rule; Windows policies are not changed).
- While connected, “Send Ctrl+Alt+Del” appears in the “⋮” menu.
- File transfer still uses the normal connection (TCP 49000), so it is unavailable while the screen is locked.
- After updating the host, the service executable stays old. Use “Disable” → “Enable” to reinstall it.

## Things to know

- **Firewall:** The installer creates rules allowing inbound UDP 49000 (video and input) and TCP 49000 (file transfer and clipboard) on all profiles (private, public, and so on). Enabling lock screen & UAC support also adds a UDP 49001 rule. They are open even on untrusted networks, so do not give the key to anyone.
- **IPv6:** Only IPv4 is supported.
- **Microphone:** There is no feature to send your phone's microphone to the PC. Making it appear as the PC's microphone requires a (signed) virtual microphone driver. As an alternative, use your phone keyboard's voice input and send the text.
- **Clock:** A connection request is only accepted when the PC and device clocks are within 60 seconds. If they differ, the PC log shows the difference.
- **Update check:** The host fetches update info from GitHub's `releases/latest` shortly after start and every 6 hours. Only a normal HTTPS request is sent. To stop it, create an empty file named `no-update-check` in `%APPDATA%\Deskport`.
- **Concurrent connections:** Up to 8 streams.
- **Camera diagnostic log:** `vcam.log` is written only while the file `C:\ProgramData\DeskportVCam\log.on` exists.

## Build

Requirements: Rust, Android Studio (JBR and SDK).

```powershell
# Get the source
git clone https://github.com/ganondorofu/deskport.git
cd deskport

# Virtual camera DLL (build first; it is embedded in the host)
cd vcam
cargo build --release          # vcam\target\release\deskport_vcam.dll

# Host
cd ..\host
cargo build --release          # host\target\release\deskport-host.exe

# App
cd ..
.\gradlew.bat assembleDebug    # app\build\outputs\apk\debug\app-debug.apk
```

Releases (signing and publishing to GitHub Releases) are done with `scripts\release.ps1` (it also builds the virtual camera). Keep the signing keys outside the repository (the sibling `deskport-keys`, or the folder in the `DESKPORT_KEYS` environment variable).

## Layout

| Path | Contents |
|---|---|
| `app/` | Android app (Kotlin, Jetpack Compose) |
| `host/` | PC host (Rust, Tauri): streaming, input, audio, tray, window, installer, updater, the lock screen & UAC service, camera receiving |
| `vcam/` | Virtual camera (Rust; the Windows 11 Media Foundation virtual camera media source. `vcam-tool` is for testing) |

The wire formats are described at the top of `host/src/serve.rs` and `host/src/crypto.rs`.

## License

MIT License (`LICENSE`).
