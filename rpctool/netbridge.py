"""rpc bridge: this computer's connection for every board plugged into it, all the time.

A board running firmware from v2.0.69x has a second USB serial port, "RPCortex
Network", that carries nothing but bridge frames (LINK.md, "The network port").
This finds those ports, opens each one, offers the bridge, and answers whatever
the board asks for, from any task on it: a background job, a service, a package's
own work, a Nova D1 menu. When a board is unplugged or restarts it waits for the
port to come back and offers again.

The console is not involved, so `rpc term`, `rpc exec`, Studio and the browser
tool keep working beside it.

Requests from a board are treated as coming from a program, not a person, so the
computer's own network (its loopback, link-local and private addresses: the
router, printers, other machines) is refused unless --lan is given.
"""
import os
import sys
import threading
import time

from . import transport
from . import bridge as _bridge
from .errors import DeviceGone, RpcError
from .term import TermBridge, _Wire

OFFER = b"\x1e\x1f"
REOFFER_S = 5.0          # until the board says BRIDGE on
KEEP_S = 30.0            # then again this often while the port is quiet: a board that
                         # withdrew its offer (a fetch went unanswered) takes it back
SCAN_S = 1.0             # how often to look for ports


def network_ports():
    """The network ports of the boards plugged in now."""
    return [p.device for p in transport.list_candidates(include_network=True) if p.network]


class Port(object):
    """One board's network port, served until it goes away."""

    def __init__(self, device, bridge, log):
        self.device = device
        self.bridge = bridge
        self.log = log
        self.stop = threading.Event()

    def run(self):
        ser = transport.open_serial(self.device)
        t = transport.Transport(ser, self.device)
        wire = _Wire(t)
        tb = TermBridge(self.bridge, wire, status=None)
        said = False
        last_offer = 0.0
        last_rx = 0.0
        try:
            while not self.stop.is_set():
                now = time.monotonic()
                due = REOFFER_S if not tb.offered else KEEP_S
                # Never in the middle of a fetch: the board reads its answers as
                # lines, and an offer arriving there would spoil one.
                if (now - last_offer >= due and not wire.fetching.is_set()
                        and now - last_rx >= 2.0):
                    wire.write(OFFER)
                    last_offer = now
                data = t.read(0.05)
                if data:
                    last_rx = time.monotonic()
                    tb.feed(data)            # anything but frames is dropped: there is none
                if tb.offered and not said:
                    self.log("{}: online through this computer".format(self.device))
                    said = True
            return "stopped"
        except DeviceGone:
            return "unplugged"
        finally:
            tb.stop()
            t.close()


def serve(lan=False, allow=None, max_bytes=None, port=None, log=None, once=False):
    """Serve every board's network port until interrupted. `once` returns when
    there is nothing left to serve (for tests)."""
    log = log or (lambda text: sys.stderr.write("[rpc bridge] {}\n".format(text)) and sys.stderr.flush())
    policy = _bridge.Policy(allow=allow, max_bytes=max_bytes or _bridge.DEFAULT_MAX,
                            block_private=not lan)
    bridge = _bridge.Bridge(policy=policy, log=log)
    active = {}
    lock = threading.Lock()

    def worker(device):
        try:
            why = Port(device, bridge, log).run()
            log("{}: {}".format(device, why))
        except RpcError as e:
            log("{}: {}".format(device, e))
            time.sleep(3)                   # a port that will not open: do not spin on it
        except Exception as e:              # one board never takes the others down
            log("{}: stopped: {}".format(device, e))
            time.sleep(3)
        finally:
            with lock:
                active.pop(device, None)

    log("serving the network to boards on this computer{} (Ctrl+C stops)".format(
        "" if not lan else ", this computer's own network included"))
    told = False
    while True:
        if port:
            # A named port that has gone (unplugged, or a restart not yet back)
            # is waited for, not opened over and over.
            found = [port] if os.path.exists(port) or not port.startswith("/dev/") else []
        else:
            found = network_ports()
        if not found and not told:
            log("waiting for a board (its firmware needs the network port: v2.0.69x or later)")
            told = True
        with lock:
            for d in found:
                if d not in active:
                    th = threading.Thread(target=worker, args=(d,), daemon=True)
                    active[d] = th
                    th.start()
            busy = bool(active)
        if once and not busy:
            return
        time.sleep(SCAN_S)


# --- running it at login ------------------------------------------------------------

UNIT = """[Unit]
Description=RPCortex network bridge (rpc bridge): this computer's connection for RPCortex boards

[Service]
ExecStart={cmd}
Restart=always
RestartSec=3

[Install]
WantedBy=default.target
"""

PLIST = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>app.novalabs.rpcortex-bridge</string>
  <key>ProgramArguments</key><array>{args}</array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
</dict></plist>
"""


def _self_command(extra):
    """How to run `rpc bridge` again: the frozen program, or this Python and rpc.py."""
    if getattr(sys, "frozen", False):
        return [sys.executable, "bridge"] + extra
    main = os.path.abspath(sys.argv[0])
    return [sys.executable, main, "bridge"] + extra


def install(extra, log=print):
    """Start `rpc bridge` at every login, and now. Returns a line saying what was done."""
    import subprocess
    cmd = _self_command(extra)
    if sys.platform.startswith("linux"):
        d = os.path.join(os.path.expanduser("~"), ".config", "systemd", "user")
        os.makedirs(d, exist_ok=True)
        path = os.path.join(d, "rpcortex-bridge.service")
        quoted = " ".join('"{}"'.format(c) if " " in c else c for c in cmd)
        with open(path, "w") as f:
            f.write(UNIT.format(cmd=quoted))
        subprocess.call(["systemctl", "--user", "daemon-reload"])
        rc = subprocess.call(["systemctl", "--user", "enable", "--now", "rpcortex-bridge.service"])
        return "systemd user service {} ({})".format(path, "running" if rc == 0 else "could not start: see systemctl --user status rpcortex-bridge")
    if sys.platform == "darwin":
        d = os.path.join(os.path.expanduser("~"), "Library", "LaunchAgents")
        os.makedirs(d, exist_ok=True)
        path = os.path.join(d, "app.novalabs.rpcortex-bridge.plist")
        args = "".join("<string>{}</string>".format(c) for c in cmd)
        with open(path, "w") as f:
            f.write(PLIST.format(args=args))
        subprocess.call(["launchctl", "unload", path], stderr=subprocess.DEVNULL)
        rc = subprocess.call(["launchctl", "load", path])
        return "launch agent {} ({})".format(path, "loaded" if rc == 0 else "could not load")
    if os.name == "nt":
        d = os.path.join(os.environ.get("APPDATA", ""), "Microsoft", "Windows", "Start Menu",
                         "Programs", "Startup")
        path = os.path.join(d, "rpcortex-bridge.cmd")
        with open(path, "w") as f:
            f.write('@start "" /min ' + " ".join('"{}"'.format(c) for c in cmd) + "\r\n")
        return "{} (runs at the next login)".format(path)
    raise RpcError("Starting at login is not set up for this system; run 'rpc bridge' yourself.")


def uninstall():
    import subprocess
    if sys.platform.startswith("linux"):
        subprocess.call(["systemctl", "--user", "disable", "--now", "rpcortex-bridge.service"])
        path = os.path.join(os.path.expanduser("~"), ".config", "systemd", "user", "rpcortex-bridge.service")
    elif sys.platform == "darwin":
        path = os.path.join(os.path.expanduser("~"), "Library", "LaunchAgents", "app.novalabs.rpcortex-bridge.plist")
        subprocess.call(["launchctl", "unload", path], stderr=subprocess.DEVNULL)
    elif os.name == "nt":
        path = os.path.join(os.environ.get("APPDATA", ""), "Microsoft", "Windows", "Start Menu",
                            "Programs", "Startup", "rpcortex-bridge.cmd")
    else:
        return "nothing to remove"
    try:
        os.remove(path)
        return "removed " + path
    except OSError:
        return "nothing was installed"
