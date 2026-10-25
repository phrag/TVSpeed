# TV Speed

A speed test for Android TV / NVIDIA Shield that you can drive with the remote:
one button, big numbers. Plain Kotlin, no third-party dependencies. GPL-3.0.

It answers "why does this buffer?" rather than just "how fast is my line?":

- **Download / upload** over 6 / 4 parallel streams for 12 s each. The first 2 s
  (TCP slow start) are dropped and the rest averaged.
- **Ping and jitter** at idle, measured as TCP connect time to the server. That is one
  round trip with no server processing in it.
- **Latency under load.** Ping is measured again while each transfer runs. A large jump
  (bufferbloat, or a congested VPN tunnel) is a common cause of buffering on a connection
  that looks fast.
- **What it's measuring.** The app shows Wi-Fi vs Ethernet, band, link rate and signal,
  and whether a **VPN** is on. It also shows the server's city and country (also listed
  with each recent run) and the public IP the test exits from, so you can tell a VPN result
  from a line result.
- **Recent runs** with VPN runs highlighted, so you can compare "VPN on" against "VPN off".
- **A verdict** in streaming terms, such as "Plenty for 4K60" or "Good for 1080p. 4K will
  likely buffer", plus a warning when latency rises under load.

Packet loss can't be measured over HTTP, so the app doesn't report it.

## Servers

Public [LibreSpeed](https://github.com/librespeed/speedtest) servers run by European
non-profits and research networks: no key, no ads or tracking, and no CDN company
in the path. The app ranks them by TCP connect time and uses the nearest. If that
server refuses (HTTP 429/403, as rate limits on shared VPN exits do) or fails, the run
starts over on the next one.

| Server | Operator | Base URL |
|---|---|---|
| CESNET, Prague | Czech research network | `https://speedtest.cesnet.cz/backend` |
| CZ.NIC, Prague | .cz registry (non-profit) | `https://librespeed.turris.cz/backend` |
| GARR, Rome | Italian research network | `https://st-be-rm2.infra.garr.it` |
| SOX, Belgrade | Serbian internet exchange | `https://speedtest1.sox.rs/librespeed/backend` |

| What | Endpoint |
|---|---|
| Download | `GET <base>/garbage.php?ckSize=25` (25 MiB per request) |
| Upload | `POST <base>/empty.php` (8 MB streamed bodies) |
| Exit IP | `GET <base>/getIP.php`, without `isp`, so no third-party IP lookup |
| Latency | TCP connect to the server's port 443 |

Requests send `Accept-Encoding: identity` so the byte count is what crossed the wire.

## Build

```bash
./gradlew :app:assembleDebug     # or open in Android Studio → Build APK
```

CI (`.github/workflows/build.yml`) builds and lints every push and pull request. Pushes
to `main` also refresh a rolling **Latest build** release (`tvspeed.apk`). Builds are
signed with the committed `app/debug.keystore`, so new APKs install over old ones.

## Install

```bash
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app is called **TV Speed** on the home screen.

## Layout

```
app/src/main/java/com/phrag/tvspeed/
├── MainActivity.kt   # the screen: gauge, tiles, verdict, recent runs (D-pad, plain Views)
├── SpeedTest.kt      # the measurement: phases, parallel streams, latency probes
├── GaugeView.kt      # log-scale speedometer (0–1000 Mbps)
├── NetInfo.kt        # Wi-Fi/Ethernet, VPN, Wi-Fi band/link/signal
├── History.kt        # last few runs (SharedPreferences)
└── Colors.kt
```
