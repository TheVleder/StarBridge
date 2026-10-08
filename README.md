# StarBridge

**Control a Celestron NexStar telescope from any phone, tablet or computer. No Celestron app, no WiFi adapter, no computer required: a cable and an old Android phone are enough.**

*[Leer en español](README.es.md)*

An old Android phone (or a Windows PC) is plugged into the telescope's hand control with a USB cable and becomes its **"brain"**. It serves a web app over your local WiFi: open it on an iPhone, an Android or any browser and you can move the telescope, GoTo thousands of objects, align it with one or two stars, compensate the gear backlash and even **live-stack photos (EAA)** with the phone on the eyepiece. No internet is needed in the field.

> ⚠️ StarBridge is a hobby project tested on one telescope (NexStar 130 SLT with the classic hand control). Keep an eye on the telescope while it moves and **never point it at the Sun** without a proper solar filter.

---

## Why StarBridge

**It works on its own.** You need nothing from Celestron except the telescope: no SkyPortal WiFi module, no Celestron app, no account. There is also no laptop, Raspberry Pi or internet connection to set up. Just the hand-control cable and a phone you probably already have in a drawer.

**An Android phone as the telescope's brain.** As far as we could find, nobody else offers this. Today, an Android phone can only join in as part of a chain:

- **A USB→network bridge app plus a planetarium app.** For example, *BT/USB/TCP Bridge Pro* with Stellarium Mobile PLUS or SkySafari. That is two apps (paid), and the phone only relays bytes.
- **An INDI client** (such as Telescope.Touch). It needs a computer or Raspberry Pi next to the telescope running the INDI server.
- **SkySafari on its own.** Its developers have said it does not control a telescope through the Android USB port.

StarBridge turns the phone itself into the whole system:
- it reads the GPS (place and time) and its sensors;
- it aligns the telescope with its own pointing model, with no hand-control alignment and no level tripod needed;
- it computes GoTo and tracking, and compensates the backlash;
- it serves the controls to **any** device as a web page, so the iPhone needs no app;
- it can live-stack photos with that same phone on the eyepiece.

It is free and source-available. If you know of something similar, please open an issue and we will gladly correct this.

## Compatible telescopes

StarBridge speaks the **NexStar hand-control protocol**, which Celestron has used in all its computerized mounts since the NexStar GPS (2001). It connects through the hand control's serial/USB port, not the AUX port of the mount. Only the **NexStar 130 SLT** (classic hand control, firmware 5.x) has been tested on real hardware so far. Reports for other models are welcome.

| Support | Mounts | How |
|---|---|---|
| ✅ **Full** (alt-az) | NexStar **SLT** (102, 114, 127, 130 SLT…), NexStar **SE** (4SE, 5SE, 6SE, 8SE), NexStar **Evolution**, **CPC** / CPC Deluxe, NexStar **GT**, **i-Series** (5i, 8i), NexStar **GPS**, **SkyProdigy**, **LCM**, **Cosmos**, **StarSeeker** | StarBridge aligns itself (1–2 stars, sensors, tilted base), runs GoTo and tracking and takes up the backlash. You can also use the hand control's own alignment. |
| 🟡 **Hand-control mode** (equatorial) | **Advanced VX** (AVX), **Advanced GT** (CG-5), **CGEM** / CGEM II / CGEM DX, **CGE** / CGE Pro, **CGX** / CGX-L | Align with the hand control as usual; StarBridge then does GoTo with that alignment, tracks equatorially and gives you every remote feature. StarBridge's own alignment is alt-az only. |
| ❌ **Not supported** | The original NexStar 5/8 (1999) and Ultima 2000 (an older protocol); telescopes without a NexStar hand control (e.g. StarSense Explorer, which has no motors); non-Celestron mounts | Live stacking still works with any mount: put the phone on the eyepiece. |

### Full list of compatible Celestron telescopes

**Alt-azimuth (full support: StarBridge aligns, points and tracks by itself)**
- **Celestron NexStar SLT:** NexStar 90SLT, NexStar 102SLT, NexStar 114SLT, NexStar 127SLT, NexStar 130SLT
- **Celestron NexStar SE:** NexStar 4SE, NexStar 5SE, NexStar 6SE, NexStar 8SE
- **Celestron NexStar Evolution:** NexStar Evolution 6, NexStar Evolution 8, NexStar Evolution 8 HD (EdgeHD), NexStar Evolution 9.25
- **Celestron CPC and CPC Deluxe:** CPC 800, CPC 925, CPC 1100, CPC Deluxe 800 HD, CPC Deluxe 925 HD, CPC Deluxe 1100 HD
- **Celestron NexStar GPS:** NexStar 8 GPS, NexStar 11 GPS
- **Celestron NexStar i-Series:** NexStar 5i, NexStar 8i
- **Celestron NexStar GT:** NexStar 60GT, NexStar 80GT, NexStar 80GTL, NexStar 102GT, NexStar 114GT (hand controls older than firmware 2.2 have fewer functions)
- **Celestron SkyProdigy:** SkyProdigy 70, SkyProdigy 90, SkyProdigy 102, SkyProdigy 130
- **Celestron LCM:** LCM 60, LCM 80, LCM 114
- **Celestron Cosmos:** Cosmos 90GT WiFi

**German equatorial (hand-control mode: align with the hand control, StarBridge does the rest)**
- **Celestron Advanced VX (AVX):** the mount on its own and its kits (AVX 6" and 8" SCT, AVX 8" EdgeHD, AVX 6" refractor, AVX 8" Newtonian…)
- **Celestron Advanced GT (CG-5 GT):** the mount and its kits (C6-SGT, C8-SGT, C9.25-SGT, C11-SGT…)
- **Celestron CGEM, CGEM II and CGEM DX:** the mounts and their kits (CGEM 800, 925, 1100, CGEM II 800/925/1100, EdgeHD…)
- **Celestron CGE and CGE Pro**
- **Celestron CGX and CGX-L**

If your telescope has a Celestron NexStar or NexStar+ hand control and is not listed, it very likely works too. Try it and tell us.

StarBridge is a free alternative to the **Celestron SkyPortal WiFi module**, SkyFi, StarFi and other WiFi adapters. It lets you control a Celestron telescope from an Android phone, an iPhone or a PC with just a USB cable, with no extra hardware.

**Hand control:** firmware **2.2 or newer** is recommended; it is shown on the hand control at start-up. Older versions lack the precise position commands. The **NexStar+** hand control works too, through its mini-USB port.

## What you need

| | |
|---|---|
| **Telescope** | A Celestron NexStar GoTo mount with its hand control: see [Compatible telescopes](#compatible-telescopes). |
| **Cable** | USB → serial cable for the hand control: an **RJ9/RJ22 4P4C** cable to the port at the bottom of the classic hand control (e.g. PL2303GT chip). FTDI, CP210x and CH34x chips also work. NexStar+ hand controls have their own mini-USB port. |
| **Brain** — option A | An **Android 10 or newer** phone with **USB OTG** (USB‑C or a USB‑A→USB‑C/micro‑USB OTG adapter). |
| **Brain** — option B | A **Windows 10/11 (64-bit)** PC. Nothing else to install: Java is included. |
| **Remote** | Any phone, tablet or computer with a modern browser (iPhone Safari 13.1+, Android Chrome…) on the same WiFi. |

Only the device with the cable is the brain; everything else is a remote. If an Android and a PC are on the same WiFi they find each other automatically.

## Download

Get the latest files from the **[Releases](../../releases/latest)** page:

- `StarBridge-<version>.apk` — the Android app.
- `StarBridge-PC-<version>-windows.zip` — the Windows program (portable, no installer needed).

## Install on Android

1. Download the `.apk` on the phone and open it. Android will ask to **allow installing apps from this source** (your browser or file manager): allow it. This is normal for apps outside Google Play.
2. Open **StarBridge**. The first time it asks for the **language** (English / Español) and shows a short checklist.
3. Plug the cable into the phone (with the OTG adapter if needed) and turn the telescope on. **The app opens by itself**: accept the USB permission ("always use") and location (GPS gives the time and your position).
4. Tap **"Allow the battery exception"** (menu ⋮ › Battery). Huawei, Xiaomi, Samsung… kill background apps otherwise.

> **You do NOT need USB debugging, developer options or root.** USB debugging is only for developers who install the app from a computer. Normal users just install the APK.

## Install on Windows

1. Unzip `StarBridge-PC-<version>-windows.zip` anywhere (e.g. Documents) and double-click **`StarBridge.exe`**.
   - Windows SmartScreen may warn about an unknown publisher (the program is not code-signed): click **More info → Run anyway**.
2. The first time it asks for the **language** and shows what is needed.
3. **Cable driver:** PL2303 cables need the [Prolific driver](https://www.prolific.com.tw/) (Windows Update usually installs it when you plug the cable in). FTDI, CP210x and CH34x usually install by themselves. If several COM ports exist, choose the cable's in **Settings**.
4. When Windows Firewall asks, **allow access on private networks**, so phones can connect.
5. StarBridge keeps running as an **icon next to the clock**: use it to open the panel again, show the QR for the phone, or quit (quitting stops the telescope).

Prefer a real installer? See [Building from source](#building-from-source) (`packaging/windows/StarBridge.iss`, Inno Setup).

## First night

1. Turn the telescope on. **No hand-control alignment needed.**
2. Put the brain and the remote on the **same WiFi**. In the field, with no router, create a **hotspot on the iPhone** and join it from the Android/PC (no SIM or internet needed).
3. **Scan the QR** shown by the brain (Android screen, or PC: Settings › Connect the iPhone / tray icon) with the phone's camera. The link includes an **access code**: without it nobody else on the network can control the telescope. Add it to the home screen for next time.
4. Tap **Align**: StarBridge suggests a bright star, does the GoTo, you centre it with the arrows and press **"It's centred"**. A second star gives full accuracy (the tripod does not need to be level).
5. Search (`M31`, `31`, `andromeda`, `saturn`…) and tap **Go**. The **STOP** button is always visible, and if the remote loses WiFi the telescope **stops by itself in under a second**.
6. With Android as the brain you can now turn its screen off or use **Black screen** (menu ⋮).

## Features

- **Sky map** in real time with the telescope's position; tap an object to go to it.
- **Search** the whole NGC and IC (~12,100 deep-sky objects with Messier, Caldwell, Barnard…), 41,000+ stars to magnitude 8, the Moon and planets, offline. Every designation works: `M31`, `31`, `NGC 224`, `PGC 2557`, `α CMa`, `61 Cyg`, `HIP 32349`, `HD 48915`; typos are tolerated. The lists show what **your** telescope can reach (set its aperture), and the map shows fainter stars and galaxies as you zoom in.
- **Own pointing model**: 1–N star alignment, tilted base, GoTo in two legs, tracking by variable rate. Also works with the hand control's alignment.
- **Backlash compensation**, measured with the phone's gyroscope or by centring a star twice.
- **Live stacking (EAA)** with the Android phone on the eyepiece: RAW long exposures, automatic calibration, registration, quality control and stacking; saves FITS/TIFF (linear) + JPEG.
- **PC panel**: large sky map with constellations, keyboard control, GoTo to coordinates, tracking charts, raw console, visible-sky zones, multi-window.
- **Red night mode**, English and Spanish.
- **Diagnostics**: what the hand control answered, for when something does not move.

## Frequently asked questions

**How can I control my Celestron telescope with my phone?**
Install StarBridge on an Android phone and plug that phone into the telescope's hand control with a USB cable. Then scan the QR code it shows with any other phone (an iPhone too) and you get the controls in the browser. You can also use the Android phone itself as the remote.

**Can I use my Celestron NexStar without the SkyPortal WiFi module?**
Yes. StarBridge replaces the SkyPortal WiFi module (and SkyFi, StarFi and similar adapters). All you need is a USB cable for the hand control.

**Is there a free alternative to the Celestron WiFi adapter?**
StarBridge is free for personal and any other noncommercial use. If you already have an old Android phone, the only thing to buy is the cable (around 10–20 €/$).

**Can I control a Celestron telescope with an Android phone over USB?**
Yes, that is exactly what StarBridge does. Any Android 10 or newer phone with USB OTG works; most phones from the last years have it. You may need a small USB‑A → USB‑C OTG adapter.

**Can I control my Celestron NexStar with an iPhone?**
Yes, as the remote. An iPhone cannot drive the cable itself, so an Android phone or a Windows PC has to be plugged into the telescope. The iPhone then opens the controls in Safari; no app is needed.

**How do I connect my Celestron NexStar to my laptop or Windows PC?**
Plug the USB cable into the hand control and the PC, install the cable's driver if Windows asks, and run StarBridge for Windows. It finds the port by itself and opens the control panel.

**What cable do I need to connect the NexStar hand control to a phone or PC?**
A USB → serial cable with an RJ9/RJ22 (4P4C, like an old phone handset) plug for the port at the bottom of the hand control. Cables with a Prolific PL2303, FTDI, CP210x or CH340 chip work. NexStar+ hand controls have a mini-USB port, so a normal USB cable is enough. Plug it into the hand control, **not** the AUX port of the mount.

**Do I have to align the telescope with the hand control first?**
No. Just switch the telescope on. StarBridge suggests a bright star, points at it, and you centre it with the arrows. One star is enough to start and two give full accuracy, even if the tripod is not level. (Equatorial mounts are aligned with the hand control as usual.)

**Does it work without internet, out in the countryside?**
Yes. Everything works offline. With no router, turn on your iPhone's hotspot and join it from the Android phone or the PC; no SIM card or mobile data is used.

**Which Celestron telescopes does it work with?**
All NexStar GoTo models with a hand control: SLT, SE, Evolution, CPC, GT, GPS and more. Equatorial mounts (AVX, CGEM, CGX…) work in hand-control mode. See [Compatible telescopes](#compatible-telescopes).

**I'm a beginner and not good with technology. Is it hard?**
It is designed for that. The app asks for your language, shows a short checklist of what is needed, and opens by itself when you plug in the cable. You don't need USB debugging, developer options, root or a computer.

**The telescope does not move or the hand control does not answer. What do I check?**
- The telescope is switched on and the cable is in the **hand control** (bottom port), not the mount's AUX port.
- On Android: accept the USB permission. Some phones (Xiaomi, OnePlus, Honor…) have an **OTG** switch in Settings that must be on.
- On Windows: install the cable's driver and, if there are several COM ports, choose the right one in Settings.
- *More → Diagnostics* shows exactly what the hand control answered.

**Can I take photos of galaxies and nebulae with my phone through the telescope?**
Yes. Put the Android phone on the eyepiece with a phone adapter and press *Start* in the *Imaging* tab. StarBridge takes many photos and stacks them live, so faint objects slowly appear on screen (EAA).

**Is it safe? Could someone else control my telescope?**
Only devices with the access code in the QR link can connect, and you can change the code at any time. The telescope also stops by itself if the remote loses the connection.

**Is it free? Can I sell it?**
It is free to use, share and modify for noncommercial purposes. Selling it or using it commercially requires the author's permission ([license](LICENSE.md)).

## Building from source

Requirements: **JDK 17**; for the Android app, the Android SDK (`local.properties` with `sdk.dir`).

```bash
./gradlew :imaging:test :core:test :server:test :desktop:test   # tests
./gradlew :app:assembleRelease          # APK → app/build/outputs/apk/release/
./gradlew :desktop:packageWindows       # Windows zip → build/release/ (run on Windows)
./gradlew :devserver:run                # web UI with a simulated telescope → http://localhost:8099
```

- **Release signing:** put your own keystore in `~/.starbridge-release/` (`keystore.properties` with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`) or set `STARBRIDGE_STORE_FILE`, `STARBRIDGE_STORE_PASSWORD`, `STARBRIDGE_KEY_ALIAS`, `STARBRIDGE_KEY_PASSWORD`. Without them the release APK is signed with the debug key. Never commit a keystore.
- **Windows installer:** after `packageWindows`, compile `packaging/windows/StarBridge.iss` with [Inno Setup 6](https://jrsoftware.org/isinfo.php) (`iscc packaging\windows\StarBridge.iss`).
- **Translations:** the UI source text is Spanish; `python tools/i18n/build.py` regenerates `app/src/main/assets/web/i18n-en.js` from `tools/i18n/en_*.py`.
- CI: `codemagic.yaml` (Codemagic).

### Project layout

| Module | What |
|---|---|
| `imaging/` | Pure Kotlin stacking engine (calibration, star detection, registration, stacking, FITS/TIFF). |
| `core/` | Pure Kotlin: NexStar protocol, driver, watchdog, pointing model, GoTo/tracking, catalog, astronomy, backlash, imaging session, LAN discovery. Simulators in `core/src/testFixtures`. |
| `server/` | HTTP + WebSocket routes (Ktor), shared by every platform. |
| `app/` | Android: foreground service, USB serial, Camera2, sensors, QR screen; web UI in `app/src/main/assets/web`. |
| `desktop/` | The PC program (jSerialComm COM port) and its panel (`desktop/src/main/resources/desk`). |
| `devserver/` | Development only: the real core against simulated mount and camera. |
| `docs/` | Architecture, NexStar protocol, backlash, plans (Spanish). |

## License

StarBridge is **source-available** under the **[PolyForm Noncommercial License 1.0.0](LICENSE.md)**: you may use, study, modify and share it for any **noncommercial** purpose (personal use, astronomy clubs, schools, research…). **Selling it or using it commercially is not allowed** without the author's permission. See [NOTICE.md](NOTICE.md).

## Credits

- Deep-sky catalog: [OpenNGC](https://github.com/mattiaverga/OpenNGC) by Mattia Verga — CC BY-SA 4.0.
- Stars: [HYG Database v4](https://codeberg.org/astronexus/hyg) by David Nash — CC BY-SA 4.0.
- Constellation figures: [d3-celestial](https://github.com/ofrohn/d3-celestial), © 2015 Olaf Frohn — BSD-3-Clause.
- Libraries: Ktor, kotlinx.coroutines/serialization, usb-serial-for-android, jSerialComm (see [NOTICE.md](NOTICE.md)).

Celestron and NexStar are trademarks of Celestron, LLC. StarBridge is not affiliated with or endorsed by Celestron.
