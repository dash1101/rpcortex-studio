"""`rpc web`: the browser tool is served from localhost with types a browser
will run as modules."""
import os
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import support  # noqa: E402,F401
from rpctool import web  # noqa: E402
from rpctool.errors import RpcError  # noqa: E402

try:
    from urllib.request import urlopen
except ImportError:  # pragma: no cover
    raise


class TestWeb(unittest.TestCase):
    def test_serves_modules_as_javascript(self):
        d = tempfile.mkdtemp()
        os.makedirs(os.path.join(d, "js"))
        with open(os.path.join(d, "index.html"), "w") as f:
            f.write("<html><body>tool</body></html>")
        with open(os.path.join(d, "js", "link.js"), "w") as f:
            f.write("export const x = 1;")
        self.assertEqual(web.find_web_dir(d), os.path.abspath(d))
        srv = web.make_server(d, 0)
        th = threading.Thread(target=srv.serve_forever)
        th.daemon = True
        th.start()
        self.addCleanup(srv.server_close)
        self.addCleanup(srv.shutdown)
        base = "http://127.0.0.1:{}/".format(srv.server_address[1])
        r = urlopen(base)
        self.assertIn(b"<html", r.read().lower())
        r = urlopen(base + "js/link.js")
        self.assertTrue(r.headers["Content-Type"].startswith("text/javascript"))
        self.assertEqual(srv.server_address[0], "127.0.0.1")

    def test_no_local_copy_means_the_hosted_one(self):
        old = os.environ.pop("RPC_WEB_DIR", None)
        self.addCleanup(lambda: old is not None and os.environ.__setitem__("RPC_WEB_DIR", old))
        self.assertIsNone(web.find_web_dir())
        self.assertTrue(web.HOSTED_URL.startswith("https://"))

    def test_explicit_dir_must_hold_the_tool(self):
        with self.assertRaises(RpcError):
            web.find_web_dir(tempfile.gettempdir())


if __name__ == "__main__":
    unittest.main()
