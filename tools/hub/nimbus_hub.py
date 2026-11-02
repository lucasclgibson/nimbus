#!/usr/bin/env python3
"""
Nimbus hub: self-updates and cross-TV sync for the Nimbus Fire TV app, on the home network.

  python3 -I tools/hub/nimbus_hub.py --data DIR [--port 8787] [--publish] [--log FILE]

Endpoints (all under /nimbus):
  GET  /nimbus/version.json          latest build: {"versionCode", "versionName", "apk", "notes"}
  GET  /nimbus/<file>.apk            the APK itself
  GET  /nimbus/sync/<profile>        merged household state (favourites, resume points, reminders, …)
  PUT  /nimbus/sync/<profile>        merge a TV's state in; returns the merged state

Merging is per item, newest timestamp wins, deletions are tombstones – so two TVs never overwrite
each other's changes. With --publish the hub announces itself over mDNS as _nimbus._tcp so the TVs
find it without any setup: through avahi on Linux, and Windows' own responder on Windows. It runs on
anything with Python 3 and no other packages; --log keeps a log of under 3 MB for an always-on hub.
"""
import argparse
import json
import logging
import logging.handlers
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()
PROFILE = re.compile(r"^[A-Za-z0-9_-]{1,40}$")
MAX_BODY = 8 * 1024 * 1024
LOG = logging.getLogger("nimbus-hub")


def merge(old: dict, new: dict) -> dict:
    """Merge two states. Each section is {id: {"t": millis, ...}}; the newer entry for an id wins."""
    out = dict(old)
    for section, items in new.items():
        if not isinstance(items, dict):
            continue
        cur = dict(out.get(section) or {})
        for key, item in items.items():
            if not isinstance(item, dict):
                continue
            prev = cur.get(key)
            if prev is None or item.get("t", 0) >= prev.get("t", 0):
                cur[key] = item
        out[section] = cur
    out["updated"] = int(time.time() * 1000)
    return out


class Hub(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    data_dir = "."

    def log_message(self, fmt, *args):
        LOG.info("%s %s", self.address_string(), fmt % args)

    def send(self, code, body=b"", ctype="application/json"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def sync_path(self, profile):
        return os.path.join(self.data_dir, "sync", profile + ".json")

    def load(self, profile):
        try:
            with open(self.sync_path(profile)) as f:
                return json.load(f)
        except (FileNotFoundError, ValueError):
            return {}

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/nimbus/version.json":
            p = os.path.join(self.data_dir, "releases", "version.json")
            if not os.path.exists(p):
                return self.send(404, {"error": "no release published"})
            with open(p, "rb") as f:
                return self.send(200, f.read())
        m = re.match(r"^/nimbus/([A-Za-z0-9._-]+\.apk)$", path)
        if m:
            p = os.path.join(self.data_dir, "releases", m.group(1))
            if not os.path.exists(p):
                return self.send(404, {"error": "missing"})
            size = os.path.getsize(p)
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(size))
            self.end_headers()
            with open(p, "rb") as f:
                shutil.copyfileobj(f, self.wfile, 256 * 1024)
            return
        m = re.match(r"^/nimbus/sync/([^/]+)$", path)
        if m and PROFILE.match(m.group(1)):
            with LOCK:
                return self.send(200, self.load(m.group(1)))
        self.send(404, {"error": "not found"})

    def do_HEAD(self):
        path = self.path.split("?", 1)[0]
        ok = path == "/nimbus/version.json" and os.path.exists(os.path.join(self.data_dir, "releases", "version.json"))
        self.send_response(200 if ok else 404)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_PUT(self):
        m = re.match(r"^/nimbus/sync/([^/]+)$", self.path.split("?", 1)[0])
        if not m or not PROFILE.match(m.group(1)):
            return self.send(404, {"error": "not found"})
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            return self.send(413, {"error": "bad size"})
        try:
            incoming = json.loads(self.rfile.read(length))
            if not isinstance(incoming, dict):
                raise ValueError
        except ValueError:
            return self.send(400, {"error": "bad json"})
        profile = m.group(1)
        with LOCK:
            merged = merge(self.load(profile), incoming)
            os.makedirs(os.path.dirname(self.sync_path(profile)), exist_ok=True)
            tmp = self.sync_path(profile) + ".tmp"
            with open(tmp, "w") as f:
                json.dump(merged, f)
            os.replace(tmp, self.sync_path(profile))
        self.send(200, merged)


class Server(ThreadingHTTPServer):
    # On Windows SO_REUSEADDR lets a second hub bind the same port without an error.
    allow_reuse_address = sys.platform != "win32"


def lan_address():
    """This machine's address on the home network: the one its outgoing traffic uses."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("192.0.2.1", 9))  # no packets are sent: this only picks the interface
            return s.getsockname()[0]
        except OSError:
            return None


class WindowsAnnouncer:
    """
    Announces the hub with Windows' own mDNS responder (DnsServiceRegister, Windows 10 1809 and later): no
    extra process, and Windows' firewall already lets that responder answer on the home network.
    """

    def __init__(self, port):
        import ctypes
        from ctypes import wintypes as w
        self.ctypes, self.port, self.keep, self.address = ctypes, port, [], None

        class Instance(ctypes.Structure):
            _fields_ = [("pszInstanceName", w.LPWSTR), ("pszHostName", w.LPWSTR),
                        ("ip4Address", ctypes.POINTER(w.DWORD)), ("ip6Address", ctypes.c_void_p),
                        ("wPort", w.WORD), ("wPriority", w.WORD), ("wWeight", w.WORD),
                        ("dwPropertyCount", w.DWORD), ("keys", ctypes.POINTER(w.LPWSTR)),
                        ("values", ctypes.POINTER(w.LPWSTR)), ("dwInterfaceIndex", w.DWORD)]

        self.Done = ctypes.WINFUNCTYPE(None, w.DWORD, ctypes.c_void_p, ctypes.POINTER(Instance))

        class Request(ctypes.Structure):
            _fields_ = [("Version", w.ULONG), ("InterfaceIndex", w.ULONG),
                        ("pServiceInstance", ctypes.POINTER(Instance)), ("pRegisterCompletionCallback", self.Done),
                        ("pQueryContext", ctypes.c_void_p), ("hCredentials", w.HANDLE), ("unicastEnabled", w.BOOL)]

        self.Request, self.w = Request, w
        self.api = ctypes.WinDLL("dnsapi")
        self.api.DnsServiceConstructInstance.restype = ctypes.POINTER(Instance)
        self.api.DnsServiceConstructInstance.argtypes = [
            w.LPCWSTR, w.LPCWSTR, ctypes.POINTER(w.DWORD), ctypes.c_void_p, w.WORD, w.WORD, w.WORD, w.DWORD,
            ctypes.POINTER(w.LPCWSTR), ctypes.POINTER(w.LPCWSTR)]
        for fn in (self.api.DnsServiceRegister, self.api.DnsServiceDeRegister):
            fn.restype = w.DWORD
            fn.argtypes = [ctypes.POINTER(Request), ctypes.c_void_p]
        self.request = None

    def register(self, address):
        ctypes, w = self.ctypes, self.w
        if self.request is not None:
            self.api.DnsServiceDeRegister(ctypes.byref(self.request), None)
        host = socket.gethostname() + ".local"
        ip4 = w.DWORD(struct.unpack("<I", socket.inet_aton(address))[0]) if address else None
        keys, values = (w.LPCWSTR * 1)("path"), (w.LPCWSTR * 1)("/nimbus")
        instance = self.api.DnsServiceConstructInstance(
            "Nimbus hub._nimbus._tcp.local", host, ctypes.byref(ip4) if ip4 else None, None,
            self.port, 0, 0, 1, keys, values)

        def done(status, _context, _instance):
            if status == 0:
                LOG.info("announced on the network as %s (%s)", host, address)
            else:
                LOG.warning("announcing the hub failed (status %s)", status)

        callback = self.Done(done)
        request = self.Request(Version=1, InterfaceIndex=0, pServiceInstance=instance,
                               pRegisterCompletionCallback=callback, unicastEnabled=False)
        status = self.api.DnsServiceRegister(ctypes.byref(request), None)
        if status != 9506:  # DNS_REQUEST_PENDING: the callback reports the outcome
            LOG.warning("announcing the hub failed (status %s)", status)
        # Everything Windows may still use has to outlive this call.
        self.keep.append((ip4, keys, values, instance, callback, request))
        del self.keep[:-2]
        self.request, self.address = request, address

    def start(self):
        self.register(lan_address())

        def follow():
            # A new address on the home network (DHCP) gets announced too.
            while True:
                time.sleep(300)
                address = lan_address()
                if address and address != self.address:
                    self.register(address)

        threading.Thread(target=follow, daemon=True).start()


def publish(port):
    if sys.platform == "win32":
        try:
            WindowsAnnouncer(port).start()
        except (OSError, AttributeError) as e:
            LOG.warning("can't announce the hub on this version of Windows: %s", e)
    elif shutil.which("avahi-publish"):
        subprocess.Popen(["avahi-publish", "-s", "Nimbus hub", "_nimbus._tcp", str(port), "path=/nimbus"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    else:
        LOG.warning("can't announce the hub: install avahi-publish (avahi-utils)")


def setup_logging(path):
    handler = (logging.handlers.RotatingFileHandler(path, maxBytes=1_000_000, backupCount=2, encoding="utf-8")
               if path else logging.StreamHandler())
    handler.setFormatter(logging.Formatter("%(asctime)s %(message)s", "%Y-%m-%d %H:%M:%S"))
    LOG.addHandler(handler)
    LOG.setLevel(logging.INFO)
    sys.excepthook = lambda *exc: LOG.critical("hub stopped", exc_info=exc)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--publish", action="store_true", help="announce _nimbus._tcp on the network (mDNS)")
    ap.add_argument("--log", help="log to this file (kept under 3 MB) instead of stderr")
    args = ap.parse_args()
    setup_logging(args.log)
    Hub.data_dir = os.path.abspath(args.data)
    os.makedirs(os.path.join(Hub.data_dir, "releases"), exist_ok=True)
    server = Server(("0.0.0.0", args.port), Hub)  # listen first, so the hub is never announced unreachable
    if args.publish:
        publish(args.port)
    LOG.info("Nimbus hub on :%s, data in %s", args.port, Hub.data_dir)
    server.serve_forever()


if __name__ == "__main__":
    main()
