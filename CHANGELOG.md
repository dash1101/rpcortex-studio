# Changelog


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
