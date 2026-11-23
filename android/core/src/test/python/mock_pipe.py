"""Studio's mock device (tests/mockdev.py) on stdin and stdout, for the Kotlin tests.

    python3 mock_pipe.py <studio repo> '<MockDevice keyword arguments as JSON>'

Bytes on stdin go to the device as if typed on its port; what it prints comes
out on stdout. When stdin closes, a JSON summary of the device's state goes to
stderr (its files, and every password AUTH was given), so a test can check
what happened on the device side.
"""
import json
import os
import sys
import threading
import time

root = sys.argv[1]
sys.path.insert(0, os.path.join(root, "tests"))
sys.path.insert(0, root)
from mockdev import MockDevice  # noqa: E402

opts = json.loads(sys.argv[2] if len(sys.argv) > 2 else "{}")
attrs = opts.pop("_attrs", {})          # set after construction: corrupt_put and the like
dev = MockDevice(**opts)
for k, v in attrs.items():
    setattr(dev, k, v)
dev.start()
port = dev.open()
port.timeout = 0.01
out = sys.stdout.buffer
stop = threading.Event()


def pump_out():
    while not stop.is_set():
        try:
            data = port.read(4096)
        except OSError:
            break
        if data:
            out.write(data)
            out.flush()


th = threading.Thread(target=pump_out, daemon=True)
th.start()
while True:
    data = os.read(0, 4096)
    if not data:
        break
    try:
        port.write(data)
    except OSError:
        break
time.sleep(0.05)
stop.set()
state = {"files": {k: len(v) for k, v in dev.fs.files.items()}, "auths": dev.auths,
         "installed": getattr(dev, "installed", {})}
sys.stderr.write(json.dumps(state) + "\n")
sys.stderr.flush()
