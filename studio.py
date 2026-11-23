"""RPCortex Studio.

With no arguments it opens the full-screen app (`rpc tui`); with arguments it
is the `rpc` command, so one program file does both. This is the file the
standalone builds are made from.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from rpctool import __version__  # noqa: E402
from rpctool.cli import main  # noqa: E402


def run(argv=None):
    args = list(sys.argv[1:] if argv is None else argv)
    if args in (["--version"], ["-V"]):
        sys.stdout.write("rpcortex-studio " + __version__ + "\n")
        return 0
    return main(args or ["tui"])


if __name__ == "__main__":
    sys.exit(run())
