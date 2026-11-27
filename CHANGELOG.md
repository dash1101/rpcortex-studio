# Changelog




## v1.4.0, 2026-11-26

### New

- The Android app updates itself: Settings shows the version you have and the latest release, and updates in one tap. The download is checked against the release's `SHA256SUMS` and against the app itself (the same app, a newer version) before Android is asked to install it, and Android asks you first. It can look for a new version when the app opens (on by default; a quick call to GitHub, nothing downloaded without you).
- A Settings page on Android (the gear at the top right): updates, the theme (now with the phone's own Material You colours), keeping the screen on while a board is connected (on by default, so a long transfer or firmware update is not cut off), and links to the source and to report a problem.
- RPCortex Studio is in Orbit for Android: it installs and updates it like the other apps.

### Improved

- The Android app looks like Orbit now: boxes with their title in the border, square buttons, a status square on every line, and a confirmation before restarting the board.
- The launcher icon is centred on the mark, and sits inside the round icon shape on every launcher.

## v1.3.1, 2026-11-26

### Fixed

- `rpc flash` recognises RPCortex's RP2350 images from v2.0.697 on, which carry the partition table for A/B firmware and say so in a way the old check did not read.

## v1.3.0, 2026-11-25

### New

- `rpc bridge`: gives every RPCortex board plugged into this computer its connection, all the time, through the board's network port (firmware v2.0.69x and later). Any program on the board can use it, not just commands typed at its console: a background download, a service, a package's own work, a Nova D1 menu. It waits for boards, reconnects when one is unplugged or restarts, and leaves the console free for `rpc term`, Studio and the browser tool. Requests to this computer's own network (the router, other machines) are refused unless `--lan`. `rpc bridge --install` starts it at every login (systemd, launchd or the Windows Startup folder).
- `rpc ports` lists a board's network port, labelled; nothing picks it as a console.

## v1.2.0, 2026-11-25

### Improved

- Sending files is faster on firmware that allows it: `rpc put` (and the Android app) keeps the next piece on its way while the board writes the last (the `window` a board announces in its `READY`, LINK.md). 35 to 51 KB/s on a Pico 2 W; older firmware is sent to as before, one piece at a time.
- The network bridge passes the board's own User-Agent to the server, so a page answers a board going online through Studio the way it would answer the board on Wi-Fi. DuckDuckGo's text version, which the board's `browse` uses for searches, turned the bridge's own agent away.
- After following redirects, the bridge tells the board where the page ended up (`X-RPC-Final-URL`), so `browse` reads relative links against the right address.

## v1.1.0, 2026-11-23

### New

- RPCortex Studio for Android: plug a board into a phone or tablet over USB and get
  the device and its health, files both ways (each one checked with SHA-256),
  packages from the repository, the firmware and an over-the-air update, and a
  command line, in Orbit's themes, with the board's sudo asked for when it is
  needed. The link client is a Kotlin port of this one, held to the same mock
  device in its tests and run against a Pico 2 W; the app has been run against a
  board through an emulator. Its USB driver has not yet run on a phone.

## v1.0.1, 2026-11-23

### Fixed

- A wrong sudo password was asked for again with the same words, so it looked as if nothing had happened. The second and third asks say the last one was refused, in `rpc tui` and at the `rpc` prompt.
- After flashing from `rpc tui`, or an update over the air, the reconnect replaced the "flashed" message with a plain "Connected", so nothing said it had worked. Once the board is back, the status line says what was done and what it runs now.

## v1.0.0, 2026-11-23

The first release of RPCortex Studio on its own. `rpc` and `rpc tui` lived in the
RPCortex repository until now; they move here with their history, and get one
program file per system.

### New

- One program for each system: `rpcortex-studio` opens the full-screen app, and
  `rpcortex-studio <command>` is the `rpc` command (`exec`, `put`, `get`, `install`,
  `backup`, `flash`, `term`, ...).
- `rpc tui`: the device, its files, packages, firmware, logs and tasks, and a
  terminal, on one screen, over the link protocol. Nine colour themes.
- `rpc backup --with-system` keeps the settings, saved Wi-Fi networks, accounts and
  keys, and every backup says what it left out.
- The network bridge (link protocol 3): a board with no network of its own uses
  this computer's connection while `rpc` is attached, for `pkg install`, `update`,
  `wget` and the like. The computer decides what may be fetched: `http` and `https`
  only, checked on every redirect, and never this computer's own services.

### Changed

- `rpc web` opens the browser tool on the RPCortex site. `--dir` (or `RPC_WEB_DIR`)
  still serves a local copy.

### Known issues

- The Windows and macOS programs are built and tested by CI; nobody has run them
  by hand against a board yet. Linux has run against a Pico 2 W.
