# TV Speed

A speed test for Android TV / NVIDIA Shield that you can drive with the remote:
one button, big numbers. Plain Kotlin, no third-party dependencies. GPL-3.0.

It answers "why does this buffer?" rather than just "how fast is my line?":

- **Download / upload** over 6 / 4 parallel streams for 12 s each. The first 2 s
  (TCP slow start) are dropped and the rest averaged. That is what the line can carry.
- **Single-stream download** for 8 s after it. Streaming apps fetch video over one or two
  connections, and one connection often gets far less than six, especially through a VPN.
  This is the figure the verdict is based on.
- **Ping and jitter** at idle, measured as TCP connect time to the server. That is one
  round trip with no server processing in it.
- **Latency under load.** Ping is measured again while each transfer runs. A large jump
  (bufferbloat, or a congested VPN tunnel) is a common cause of buffering on a connection
  that looks fast.
- **What it's measuring.** The app shows Wi-Fi vs Ethernet, band, link rate and signal,
  and whether a **VPN** is on. It also shows the server's city and country (also listed
  with each recent run) and the public IP the test exits from, so you can tell a VPN result
  from a line result.
- **Privacy line.** Every time the app opens it shows whether a VPN is up and the public IP
  sites see (asked of a LibreSpeed server, no third-party lookup): green "Private: VPN on",
  amber "Not private: no VPN", or "Offline" when an always-on VPN with "block connections
  without VPN" has dropped.
- **Recent runs** with VPN runs highlighted. The last 20 are kept on the TV; **All runs**
  ranks each way out (line, VPN on or off, tag and test server) by median speed, so you can see
  which VPN server or protocol streams best. The **Tag** button sets a label (WireGuard, OpenVPN,
  or your own text such as a server name) that is saved with each run until you change it, and has a **Clear history** button. The exit IP
  is shown live but never saved with a run or written to logcat.
- **A verdict** in streaming terms, such as "Plenty for 4K60" or "Good for 1080p. 4K will
  likely buffer", judged on the single-stream figure, plus a warning when one connection gets
  much less than the line carries or when latency rises under load.

Packet loss can't be measured over HTTP, so the app doesn't report it.

## Servers

Public speed-test servers, mostly run by European non-profits, community networks and
research networks, plus two North American hosting providers (the public LibreSpeed list
has no non-profit servers outside Europe): no key, no ads or tracking, and no CDN company in the path.

**Automatic** (the default) picks the server nearest to the connection being tested, by
the median of five TCP connect times to each of its addresses (IPv6 and IPv4). Behind a
VPN that means nearest to the VPN exit. If that server refuses (HTTP 429/403, as rate
limits on shared VPN exits do) or fails, the run starts over on the next one. To use one
server every time, select the **Server** tile and pick it; the choice is remembered.

Automatic looks for a server in your city or country first, and only then the nearest. The
place is never worked out from your IP: by default it is the country of the TV's time zone
(read on the TV, no network lookup), and a server there is only preferred when it is about as
near as the nearest one (within 40 ms), so a VPN exit abroad still gets a server near the exit.
To choose the place, select the **Server** tile, then **Automatic looks first in…**, and pick
a country or city; a place you pick is always preferred while one of its servers answers.

| Server | Operator | Kind | Base URL |
|---|---|---|---|
| Munich, Germany | Freifunk München (community network, anycast) | OpenSpeedTest | `https://speedtest.ffmuc.net` |
| Prague, Czechia | CESNET (Czech research network) | LibreSpeed | `https://speedtest.cesnet.cz/backend` |
| Prague, Czechia | CZ.NIC (.cz registry, non-profit) | LibreSpeed | `https://librespeed.turris.cz/backend` |
| Rome, Italy | GARR (Italian research network) | LibreSpeed | `https://st-be-rm2.infra.garr.it` |
| Belgrade, Serbia | SOX (Serbian internet exchange) | LibreSpeed | `https://speedtest1.sox.rs/librespeed/backend` |
| Madrid, Spain | RedIRIS (Spanish research network) | LibreSpeed | `https://speedtest.rediris.es/backend` |
| Chicago, USA | Sharktech (US hosting provider, not a non-profit) | LibreSpeed | `https://chispeed.sharktech.net/backend` |
| Los Angeles, USA | Sharktech (US hosting provider, not a non-profit) | LibreSpeed | `https://laxspeed.sharktech.net/backend` |

| What | LibreSpeed | OpenSpeedTest |
|---|---|---|
| Download | `GET <base>/garbage.php?ckSize=25` (25 MiB) | `GET <base>/downloading` (30 MiB) |
| Upload | `POST <base>/empty.php` (8 MB streamed bodies) | `POST <base>/upload` |
| Exit IP | `GET <base>/getIP.php`, without `isp`, so no third-party lookup | not available |
| Latency | TCP connect to the server's port 443 | same |

To test against one server from adb (no fallback), start the app with its name, then press the button:

```bash
adb shell am start -S -n com.phrag.tvspeed/.MainActivity --es server GARR
```

Requests send `Accept-Encoding: identity` so the byte count is what crossed the wire.
A dropped connection gets one retry on the same server before the run moves on.

## Build

```bash
./gradlew :app:assembleDebug     # or open in Android Studio → Build APK
```

CI (`.github/workflows/build.yml`) builds and lints every push and pull request. Pushes
to `main` also refresh a rolling **Latest build** release (`tvspeed.apk`). Builds are
signed with the committed `app/debug.keystore`, so new APKs install over old ones.

## Updates

The app checks the rolling release's `version.json` at most once a day and offers a newer
build. The download's size and sha256 must match, and the APK must be this app signed with
the installed app's key (or a key it was rotated to) before it goes to Android's installer.
**Updates** (top right) shows the installed build, checks now, and turns automatic checks
off. Note that the signing key is committed, so the signer check only protects against a
tampered or swapped file, not against someone who builds their own APK with that key.

App data (`allowBackup="false"`) is never sent to cloud backup.

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
