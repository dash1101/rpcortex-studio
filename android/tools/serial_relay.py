"""Relay a board's serial console to a TCP port, for developing the Android app in an emulator.

    python3 tools/serial_relay.py /dev/ttyACM0 [port]     (default port 7777)

The emulator reaches this computer as 10.0.2.2. One client at a time; DTR is raised while one is
connected and dropped when it leaves, as a real port would be. Plain TCP: for a development
machine, never for a network anyone else is on.
"""
import socket
import sys
import threading

import serial

path = sys.argv[1]
port = int(sys.argv[2]) if len(sys.argv) > 2 else 7777
srv = socket.socket()
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", port))
srv.listen(1)
print("relaying {} on 127.0.0.1:{}".format(path, port), flush=True)
while True:
    conn, addr = srv.accept()
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    ser = serial.Serial(path, 115200, timeout=0.02)
    ser.dtr = True
    print("client", addr, flush=True)
    done = threading.Event()

    def to_client():
        while not done.is_set():
            try:
                data = ser.read(4096)
            except Exception:
                break
            if data:
                try:
                    conn.sendall(data)
                except OSError:
                    break
        done.set()

    th = threading.Thread(target=to_client, daemon=True)
    th.start()
    while not done.is_set():
        try:
            data = conn.recv(4096)
        except OSError:
            break
        if not data:
            break
        ser.write(data)
    done.set()
    th.join(1)
    ser.dtr = False
    ser.close()
    conn.close()
    print("client left", flush=True)
