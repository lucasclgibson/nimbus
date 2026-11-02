#!/usr/bin/env python3
"""
Nimbus hub: self-updates and cross-TV sync for the Nimbus Fire TV app, on the home network.

  python3 -I tools/hub/nimbus_hub.py --data DIR [--port 8787] [--publish]

Endpoints (all under /nimbus):
  GET  /nimbus/version.json          latest build: {"versionCode", "versionName", "apk", "notes"}
  GET  /nimbus/<file>.apk            the APK itself
  GET  /nimbus/sync/<profile>        merged household state (favourites, resume points, reminders, …)
  PUT  /nimbus/sync/<profile>        merge a TV's state in; returns the merged state

Merging is per item, newest timestamp wins, deletions are tombstones – so two TVs never overwrite
each other's changes. With --publish the hub announces itself over mDNS (avahi) as _nimbus._tcp so
the TVs find it without any setup.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()
PROFILE = re.compile(r"^[A-Za-z0-9_-]{1,40}$")
MAX_BODY = 8 * 1024 * 1024


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
        sys.stderr.write("%s %s\n" % (time.strftime("%H:%M:%S"), fmt % args))

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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--publish", action="store_true", help="announce _nimbus._tcp via avahi-publish")
    args = ap.parse_args()
    Hub.data_dir = os.path.abspath(args.data)
    os.makedirs(os.path.join(Hub.data_dir, "releases"), exist_ok=True)
    if args.publish and shutil.which("avahi-publish"):
        subprocess.Popen(["avahi-publish", "-s", "Nimbus hub", "_nimbus._tcp", str(args.port), "path=/nimbus"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    sys.stderr.write(f"Nimbus hub on :{args.port}, data in {Hub.data_dir}\n")
    ThreadingHTTPServer(("0.0.0.0", args.port), Hub).serve_forever()


if __name__ == "__main__":
    main()
