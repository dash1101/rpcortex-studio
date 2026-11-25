"""rpc bridge: the network port (rpctool/netbridge.py).

A fake board on a pseudo-terminal stands in for the "RPCortex Network" port: it
waits for the offer, answers it, then asks for a URL the way core/bridge.cpp does
(FETCH, FGO), acknowledges each FDATA and reads to FDONE. The web server is a
local one. Linux and macOS only (a pty).
"""
import base64
import os
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import support  # noqa: F401
from rpctool import bridge as _bridge
from rpctool import netbridge, transport

BODY = bytes(range(256)) * 12          # 3 KB, several chunks


class Site(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/data":
            self.send_response(200)
            self.send_header("Content-Length", str(len(BODY)))
            self.end_headers()
            self.wfile.write(BODY)
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, *a):
        pass


class FakeBoard(object):
    """The device end of the network port."""

    def __init__(self, fd):
        self.fd = fd
        self.buf = b""

    def write(self, text):
        os.write(self.fd, b"\x1e" + text.encode() + b"\n")

    def line(self, timeout=10):
        end = time.monotonic() + timeout
        while b"\n" not in self.buf:
            if time.monotonic() > end:
                raise AssertionError("nothing from the host: " + repr(self.buf))
            try:
                self.buf += os.read(self.fd, 4096)
            except BlockingIOError:
                time.sleep(0.01)
        line, _, self.buf = self.buf.partition(b"\n")
        return line

    def wait_offer(self, timeout=10):
        end = time.monotonic() + timeout
        while b"\x1e\x1f" not in self.buf:
            if time.monotonic() > end:
                raise AssertionError("no offer")
            try:
                self.buf += os.read(self.fd, 4096)
            except BlockingIOError:
                time.sleep(0.01)
        self.buf = self.buf.split(b"\x1e\x1f", 1)[1]
        self.write("BRIDGE on chunk=768")

    def fetch(self, rid, url):
        self.write("FETCH {} GET {}".format(rid, url))
        self.write("FGO {} chunk=768".format(rid))
        status, data, headers = None, bytearray(), []
        while True:
            raw = self.line()
            f = raw.lstrip(b"\x1e").decode()
            kind, _, rest = f.partition(" ")
            fid, _, rest = rest.partition(" ")
            if kind == "FWAIT":
                continue
            if fid != str(rid):
                continue
            if kind == "FHEAD":
                status = int(rest.split(" ")[0])
            elif kind == "FHDR":
                headers.append(base64.b64decode(rest).decode())
            elif kind == "FDATA":
                data.extend(base64.b64decode(rest))
                self.write("FACK {} {}".format(rid, len(data)))
            elif kind == "FDONE":
                return status, bytes(data), headers, rest


try:
    import serial  # noqa: F401  (the daemon opens the port with pyserial)
    HAVE_SERIAL = True
except ImportError:
    HAVE_SERIAL = False


@unittest.skipIf(os.name == "nt", "needs a pseudo-terminal")
@unittest.skipUnless(HAVE_SERIAL, "needs pyserial to open the port")
class TestNetworkPort(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), Site)
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.url = "http://127.0.0.1:{}".format(cls.srv.server_address[1])

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()

    def start(self, **kw):
        master, slave = os.openpty()
        os.set_blocking(master, False)
        path = os.ttyname(slave)
        self.log = []
        th = threading.Thread(target=netbridge.serve,
                              kwargs=dict(port=path, log=self.log.append, once=True, **kw),
                              daemon=True)
        th.start()
        return FakeBoard(master), master, slave, th

    def test_offer_then_fetches_from_any_task(self):
        board, master, slave, th = self.start(allow=["127.0.0.1"])
        try:
            board.wait_offer()
            status, data, headers, done = board.fetch(1, self.url + "/data")
            self.assertEqual((status, done), (200, "ok"))
            self.assertEqual(data, BODY)
            status, data, _, _ = board.fetch(2, self.url + "/missing")
            self.assertEqual(status, 404, "an error page is an answer")
            self.assertTrue(any("online through this computer" in x for x in self.log), self.log)
        finally:
            os.close(slave)
            os.close(master)
        th.join(10)
        self.assertFalse(th.is_alive(), "the port going away ends its service")

    def test_this_computers_network_is_refused_without_lan(self):
        board, master, slave, th = self.start()          # no allowlist, no --lan
        try:
            board.wait_offer()
            status, data, _, done = board.fetch(1, self.url + "/data")
            self.assertTrue(done.startswith("err refused"), done)
            self.assertIn("this computer", done)
        finally:
            os.close(slave)
            os.close(master)
        th.join(10)


class TestPolicyAndPorts(unittest.TestCase):
    def test_private_addresses(self):
        p = _bridge.Policy(block_private=True)
        for u in ("http://192.168.1.1/", "http://10.0.0.5/x", "http://172.16.3.4/",
                  "http://printer.local/", "http://127.0.0.1/"):
            with self.assertRaises(_bridge.Refused, msg=u):
                p.check(u)
        p.check("https://8.8.8.8/")
        _bridge.Policy(block_private=True, allow=["192.168.1.1"]).check("http://192.168.1.1/")
        _bridge.Policy().check("http://192.168.1.1/")    # rpc term / exec: as before

    def test_the_network_port_is_never_offered_as_a_console(self):
        net = transport.PortInfo("/dev/ttyACM1", "RPCortex - RPCortex Network", 0x2E8A, 0x11,
                                 interface="RPCortex Network")
        con = transport.PortInfo("/dev/ttyACM0", "RPCortex - RPCortex Console", 0x2E8A, 0x11,
                                 interface="RPCortex Console")
        self.assertTrue(net.network)
        self.assertFalse(con.network)
        self.assertIn("network port", net.label())


if __name__ == "__main__":
    unittest.main()
