#!/usr/bin/env python3
"""
Mock Xtream Codes panel for testing Nimbus end to end without touching a real provider.

  python3 -I tools/mock-xtream/server.py [host] [port] [--showcase]
  (login: demo / demo; host defaults to this machine's LAN address)

Serves: player_api.php (live / VOD / series, info calls, per-channel EPG), xmltv.php
(7 days back, 3 days ahead), a real live MPEG-TS broadcast (ffmpeg test patterns),
catch-up timeshift URLs, films and serials, and a large synthetic library with generated
artwork to stress-test the UI. Responses deliberately include the quirks real panels have
(numbers as strings, [] for empty objects, etc.).

The films and serials in library.json are real: open movies and public-domain classics.
fetch_art.py downloads their posters and stills, and they play their copies on Wikimedia
Commons. --showcase serves only those (the README screenshots are taken that way).
"""
import hashlib
import io
import json
import os
import queue
import random
import subprocess
import sys
import threading
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse
from xml.sax.saxutils import escape

from PIL import Image, ImageDraw, ImageFilter, ImageFont

def lan_address():
    """This machine's address on the local network (what the TV connects to)."""
    import socket
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        try:
            sock.connect(("192.0.2.1", 9))  # no packets are sent: this just picks the outgoing interface
            return sock.getsockname()[0]
        except OSError:
            return "127.0.0.1"


ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
# Only the real films and serials, without the synthetic library (for screenshots).
SHOWCASE = "--showcase" in sys.argv[1:]
HOST = ARGS[0] if ARGS else lan_address()
PORT = int(ARGS[1]) if len(ARGS) > 1 else 8899
BASE = f"http://{HOST}:{PORT}"
USER, PASS = "demo", "demo"
HERE = os.path.dirname(os.path.abspath(__file__))
FONT_BOLD = os.path.join(HERE, "..", "..", "app", "src", "main", "res", "font", "inter_extrabold.ttf")
FONT_REG = os.path.join(HERE, "..", "..", "app", "src", "main", "res", "font", "inter_medium.ttf")
MEDIA = os.path.join(HERE, "cache", "media")  # generated test films (see README)
LOG = lambda *a: print(time.strftime("%H:%M:%S"), *a, flush=True)

# ------------------------------------------------------------------------------------ live TV

LIVE_CATS = [
    ("1", "UK | Entertainment"), ("2", "UK | Sport"), ("3", "UK | News"), ("4", "UK | Films"),
    ("5", "UK | Kids"), ("6", "UK | Documentaries"), ("7", "XXX | Adult"), ("8", "TEST | Reliability"),
]
CHANNELS = [
    # id, num, name, cat, archive days, epg id, colour
    (101, 101, "Horizon One HD", "1", 7, "HorizonOne.tv", "#d32f2f"),
    (102, 102, "Horizon Two HD", "1", 7, "HorizonTwo.tv", "#7b1fa2"),
    (103, 103, "Harbour HD", "1", 7, "Harbour.tv", "#1976d2"),
    (104, 104, "Beacon HD", "1", 7, "Beacon.tv", "#455a64"),
    (105, 105, "Northlight HD", "1", 3, "Northlight.tv", "#c2185b"),
    (106, 106, "Lumen HD", "1", 2, "Lumen.tv", "#0288d1"),
    (107, 107, "Atlas HD", "1", 7, "Atlas.tv", "#283593"),
    (108, 108, "Vista HD", "1", 0, "Vista.tv", "#00897b"),
    (109, 109, "Quasar HD", "1", 0, "Quasar.tv", "#f9a825"),
    (110, 110, "Pulse HD", "1", 2, "Pulse.tv", "#6a1b9a"),
    (111, 111, "Laugh Lines HD", "1", 0, "LaughLines.tv", "#fbc02d"),
    (112, 112, "Velvet HD", "1", 0, "", "#ad1457"),  # no epg id: name-matched
    (113, 113, "Gold Coast HD", "1", 0, "GoldCoast.tv", "#ffa000"),
    (114, 114, "Harbour 2 HD", "1", 2, "Harbour2.tv", "#1565c0"),
    (115, 115, "Kestrel", "1", 0, "Kestrel.tv", "#2e7d32"),
    (401, 401, "Arena Sports 1", "2", 7, "Arena1.tv", "#c62828"),
    (402, 402, "Arena Sports 2", "2", 7, "Arena2.tv", "#4a148c"),
    (403, 403, "Arena Motorsport", "2", 3, "ArenaMotor.tv", "#d50000"),
    (404, 404, "Stadium 1", "2", 3, "Stadium1.tv", "#212121"),
    (405, 405, "Stadium 2", "2", 0, "Stadium2.tv", "#37474f"),
    (406, 406, "Arena Cricket", "2", 0, "ArenaCricket.tv", "#1b5e20"),
    (407, 407, "Arena Golf", "2", 0, "ArenaGolf.tv", "#33691e"),
    (501, 501, "World News 24", "3", 1, "WorldNews.tv", "#b71c1c"),
    (502, 502, "Metro News HD", "3", 1, "MetroNews.tv", "#0d47a1"),
    (503, 503, "Capital News", "3", 0, "CapitalNews.tv", "#1a237e"),
    (504, 504, "Global Report", "3", 0, "GlobalReport.tv", "#ef6c00"),
    (301, 301, "Cinema Premiere", "4", 7, "CinemaPremiere.tv", "#880e4f"),
    (302, 302, "Cinema Action", "4", 3, "CinemaAction.tv", "#bf360c"),
    (303, 303, "Cinema Comedy", "4", 0, "CinemaComedy.tv", "#f57f17"),
    (304, 304, "Film Club HD", "4", 2, "FilmClub.tv", "#4e342e"),
    (601, 601, "Kids Zone HD", "5", 2, "KidsZone.tv", "#43a047"),
    (602, 602, "Tiny Tots HD", "5", 2, "TinyTots.tv", "#fdd835"),
    (603, 603, "Toonville", "5", 0, "Toonville.tv", "#ff6f00"),
    (604, 604, "Doodle Planet", "5", 0, "DoodlePlanet.tv", "#000000"),
    (701, 701, "Pathfinder HD", "6", 0, "Pathfinder.tv", "#0277bd"),
    (702, 702, "Wild Planet", "6", 2, "WildPlanet.tv", "#ffd600"),
    (703, 703, "Nature HD", "6", 0, "Nature.tv", "#2e7d32"),
    (704, 704, "Chronicle HD", "6", 0, "Chronicle.tv", "#5d4037"),
    (705, 705, "Test HLS stream", "6", 0, "", "#607d8b"),
    (901, 901, "Adult Channel", "7", 0, "Adult1", "#e91e63"),
    # Reliability tests
    (801, 801, "Drops every 15s", "8", 0, "Drop.test", "#ef5350"),
    (802, 802, "503 twice then OK", "8", 0, "Flaky.test", "#ffa726"),
    (803, 803, "Always 404", "8", 0, "Gone.test", "#78909c"),
    (804, 804, "Freezes after 10s", "8", 0, "Freeze.test", "#26a69a"),
    (805, 805, "New timestamps on reconnect", "8", 0, "Restart.test", "#5c6bc0"),
    (806, 806, "Catch-up drops every 25s", "8", 7, "ArchiveDrop.test", "#8d6e63"),
    (807, 807, "Catch-up at real-time speed", "8", 7, "ArchiveSlow.test", "#26c6da"),
    (808, 808, "Catch-up re-stamped, drops", "8", 7, "ArchiveStamp.test", "#9ccc65"),
]
if SHOWCASE:
    # Screenshots: no test or adult channels, and plain group names.
    CHANNELS = [c for c in CHANNELS if c[3] not in ("7", "8") and c[0] != 705]
    LIVE_CATS = [(c, n.split("| ", 1)[-1]) for c, n in LIVE_CATS if c not in ("7", "8")]
CH_BY_ID = {c[0]: c for c in CHANNELS}

TITLES = {
    "1": ["Harbour Lights", "The Big Quiz", "Kitchen Nights", "Street Market", "Build It Better", "Comedy Club",
          "Who Said That?", "The Fix-Up Shop", "Country Lanes", "Cook Off", "The News Quiz", "Line of Fire",
          "Time Keepers", "Northern Street", "Valley Farm", "Bake Club", "Sunny Escapes", "Country Homes",
          "The Late Show", "Wheels", "Antique Hunters", "Dance Floor", "The Hustle", "Puzzle Masters"],
    "2": ["Live: Football", "Sunday Football", "Football Tonight", "Live Motor Racing: Qualifying", "Racing Weekly",
          "Sports News", "Live: Championship Football", "Live Cricket: Test Match", "Golf: Final Round",
          "Score Saturday", "The Football Show", "Live Boxing", "American Football: Live", "Goals of the Week",
          "Live: European Football"],
    "3": ["World News", "News at Six", "The Briefing", "Newsday", "Hard Questions", "Politics Live", "The Papers",
          "Business Report", "Breakfast", "Late Edition", "In Context", "Weather"],
    "4": ["The Last Horizon", "Midnight Protocol", "Golden Harbour", "Silent Frontier", "Crimson Tide Rising",
          "The Northern Signal", "Electric Summer", "Hidden Kingdom", "Iron Station", "Wild River",
          "Distant Light", "Frozen Echo", "Endless Garden", "Secret Machine", "Shadow Theory", "Final Crossing"],
    "5": ["Hop and Pop", "Dino Den", "Space Kids", "Ocean Pals", "Story Time", "Science Lab", "The Doodles",
          "Robot Club", "Funny Farm", "History Heroes", "Jungle Jam", "Art Party"],
    "6": ["Wild Coasts", "Deadly Creatures", "Ancient Mysteries", "Gold Hunters", "Flight Files", "Seven Wonders",
          "Rescue Files", "Car Fixers", "Mighty Machines", "Lost Expeditions", "Blue Oceans", "Pawn Masters"],
    "7": ["Late Night"],
    "8": ["Test card", "Reliability test"],
}
DESC_BITS = [
    "A gripping new episode packed with twists.", "The team face their toughest challenge yet.",
    "Featuring highlights, analysis and interviews.", "Secrets come to light as tensions rise.",
    "An unforgettable journey across breathtaking landscapes.", "Expert insight and lively debate.",
    "Our hosts are joined by special guests.", "Brand new series.", "Includes behind-the-scenes footage.",
]


def h(*parts) -> int:
    return int(hashlib.md5("|".join(map(str, parts)).encode()).hexdigest()[:8], 16)


def logo_url(ch):
    # The name is part of the address, so apps that cache images by address show a renamed channel's new logo.
    return f"{BASE}/img/logo/{ch[0]}.png?v={h(ch[2]):08x}"


def programmes(ch, start, end):
    """Deterministic schedule for a channel between two epoch seconds."""
    cid, _, name, cat, *_ = ch
    t = (start // 1800) * 1800
    out = []
    while t < end:
        r = h(cid, t)
        if cat == "3":
            dur = 1800 if r % 3 else 3600
        elif cat in ("2", "4"):
            dur = [5400, 7200, 9000, 3600][r % 4]
        else:
            dur = [1800, 3600, 3600, 2700, 5400][r % 5]
        pool = TITLES[cat]
        title = pool[r % len(pool)]
        ep = f"S{1 + r % 9} E{1 + (r >> 4) % 12}" if cat in ("1", "5", "6") else ""
        desc = " ".join(DESC_BITS[(r >> i) % len(DESC_BITS)] for i in (2, 5, 9))
        out.append((t, t + dur, title, ep, desc))
        t += dur
    return out


def xmltv_time(ts):
    return datetime.fromtimestamp(ts, timezone.utc).strftime("%Y%m%d%H%M%S +0000")


def build_xmltv():
    now = int(time.time())
    start, end = now - 7 * 86400, now + 3 * 86400
    parts = ['<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE tv SYSTEM "xmltv.dtd">\n<tv generator-info-name="nimbus-mock">\n']
    for ch in CHANNELS:
        epg = ch[5] or ch[2].replace(" HD", "").replace(" ", "") + ".uk"
        parts.append(f'<channel id="{escape(epg)}"><display-name>{escape(ch[2].replace(" HD", ""))}</display-name>'
                     f'<icon src="{escape(logo_url(ch))}"/></channel>\n')
    for ch in CHANNELS:
        if ch[0] == 705:
            continue
        epg = ch[5] or ch[2].replace(" HD", "").replace(" ", "") + ".uk"
        for s, e, title, ep, desc in programmes(ch, start, end):
            parts.append(f'<programme start="{xmltv_time(s)}" stop="{xmltv_time(e)}" channel="{escape(epg)}">'
                         f'<title lang="en">{escape(title)}</title><desc lang="en">{escape(desc)}</desc>')
            if ep:
                parts.append(f'<episode-num system="onscreen">{ep}</episode-num>')
            parts.append('</programme>\n')
    parts.append("</tv>\n")
    return "".join(parts).encode()


# ------------------------------------------------------------------------------------ VOD

# Real films and serials with their real artwork (see library.json and fetch_art.py).
LIBRARY = json.load(open(os.path.join(HERE, "library.json"), encoding="utf-8"))
ART = os.path.join(MEDIA, "art")
try:
    ART_INFO = json.load(open(os.path.join(ART, "art.json")))
except (OSError, ValueError):
    ART_INFO = {}

VOD_CATS = [("10", "EN | Featured"), ("11", "EN | Action"), ("12", "EN | Comedy"), ("13", "EN | Drama"), ("14", "EN | Sci-Fi"),
            ("15", "4K | UHD Movies"), ("16", "EN | Horror"), ("17", "EN | Family"), ("18", "EN | Thriller"),
            ("19", "EN | Originals"), ("20", "EN | Family Favourites"), ("21", "EN | Award Winners"), ("22", "XXX | Adult Films")]
WORDS_A = ["The Last", "Midnight", "Silent", "Broken", "Golden", "Hidden", "Crimson", "Northern", "Electric", "Lost",
           "Burning", "Final", "Shadow", "Iron", "Wild", "Distant", "Frozen", "Endless", "Secret", "Rising"]
WORDS_B = ["Horizon", "Protocol", "Kingdom", "Signal", "Harbour", "Empire", "Echo", "Frontier", "Garden", "Machine",
           "River", "Summer", "Code", "Station", "Orchard", "Tide", "Legacy", "Theory", "Crossing", "Light"]
FIRST = ["Olivia", "James", "Amelia", "Noah", "Isla", "Oliver", "Ava", "George", "Mia", "Arthur", "Freya", "Leo",
         "Emily", "Harry", "Grace", "Jack", "Sophie", "Thomas", "Lily", "Henry"]
LAST = ["Hartley", "Okafor", "Bennett", "Clarke", "Moreau", "Silva", "Novak", "Whitaker", "Ashworth", "Kaur",
        "Fletcher", "Lindqvist", "Doyle", "Mensah", "Garrett", "Price", "Holloway", "Reyes", "Sutton", "Wilde"]
GENRES = {"11": "Action, Adventure", "12": "Comedy", "13": "Drama", "14": "Science Fiction", "15": "Action, Sci-Fi",
          "16": "Horror, Mystery", "17": "Family, Animation", "18": "Thriller, Crime", "19": "Drama, Thriller",
          "20": "Family, Fantasy", "21": "Drama, Sci-Fi", "22": "Adult"}

MOVIES = []
for i in range(1, 1 if SHOWCASE else 1500):
    r = h("m", i)
    cat = VOD_CATS[1 + r % (len(VOD_CATS) - 1)][0]
    MOVIES.append(dict(
        id=2000 + i, cat=cat, name=f"{WORDS_A[r % 20]} {WORDS_B[(r >> 5) % 20]}" + (f" {2 + (r >> 9) % 3}" if r % 11 == 0 else ""),
        year=1985 + (r >> 3) % 40, genre=GENRES[cat], rating=round(4.5 + (r % 50) / 10, 1),
        director=f"{FIRST[(r >> 7) % 20]} {LAST[(r >> 11) % 20]}", runtime=85 + r % 70, trailer="",
        cast=", ".join(f"{FIRST[(r >> (k * 3)) % 20]} {LAST[(r >> (k * 4 + 1)) % 20]}" for k in range(4)),
        plot=f"When {FIRST[r % 20]} {LAST[(r >> 2) % 20]} uncovers a secret buried beneath {WORDS_B[(r >> 6) % 20].lower()} "
             f"bay, a race against time begins that will change everything. {DESC_BITS[r % len(DESC_BITS)]}",
    ))
# The real films: in their own categories in showcase mode, otherwise all under Featured.
FILM_CATS = list(dict.fromkeys(f["category"] for f in LIBRARY["films"]))
if SHOWCASE:
    VOD_CATS = [(str(40 + i), name) for i, name in enumerate(FILM_CATS)]
for i, f in enumerate(LIBRARY["films"]):
    MOVIES.append(dict(
        id=1000 + i, cat=str(40 + FILM_CATS.index(f["category"])) if SHOWCASE else "10", name=f["title"], year=f["year"],
        genre=f["genre"], rating=f["rating"], director=f["director"], runtime=f["runtime"], trailer="", cast=f["cast"],
        plot=f["plot"], key=f["key"], released=f["released"], country=f["country"]))
MOVIE_BY_ID = {m["id"]: m for m in MOVIES}

SERIES_CATS = [("30", "EN | Drama Series"), ("31", "EN | Comedy Series"), ("32", "EN | Crime"), ("33", "EN | Documentary Series"),
               ("34", "EN | Kids Series"), ("35", "EN | Binge-worthy")]
SERIES = []
for i in range(1, 1 if SHOWCASE else 60):
    r = h("s", i)
    cat = SERIES_CATS[r % len(SERIES_CATS)][0]
    SERIES.append(dict(
        id=5000 + i, cat=cat, name=f"{WORDS_A[(r >> 3) % 20]} {WORDS_B[(r >> 8) % 20]}" + (" (UK)" if r % 7 == 0 else ""),
        year=2005 + r % 20, seasons=1 + r % 4, eps=6 + (r >> 4) % 5, rating=round(6 + (r % 35) / 10, 1),
        genre=["Drama", "Comedy", "Crime, Drama", "Documentary", "Kids, Animation", "Drama, Thriller"][SERIES_CATS.index(next(c for c in SERIES_CATS if c[0] == cat))],
        cast=", ".join(f"{FIRST[(r >> (k * 3)) % 20]} {LAST[(r >> (k * 4 + 2)) % 20]}" for k in range(4)),
        plot=f"In {WORDS_B[r % 20].lower()} town, {FIRST[(r >> 5) % 20]} {LAST[(r >> 1) % 20]} must hold a family together as old rivalries resurface. "
             f"{DESC_BITS[(r >> 3) % len(DESC_BITS)]}",
    ))
SERIAL_CATS = list(dict.fromkeys(s["category"] for s in LIBRARY["series"]))
SERIES_CATS = ([] if SHOWCASE else SERIES_CATS) + [(str(36 + i), name if SHOWCASE else f"EN | {name}") for i, name in enumerate(SERIAL_CATS)]
for i, s in enumerate(LIBRARY["series"]):
    SERIES.append(dict(
        id=4000 + i, cat=str(36 + SERIAL_CATS.index(s["category"])), name=s["title"], year=s["year"], seasons=1,
        eps=len(s["episodes"]), rating=s["rating"], genre=s["genre"], cast=s["cast"], plot=s["plot"], key=s["key"],
        director=s["director"], released=s["released"], episodes=s["episodes"]))
SERIES_BY_ID = {s["id"]: s for s in SERIES}


def added_ago(item):
    """How long ago an item was added: the real ones are the newest, in library order."""
    if "key" in item:
        return 3600 * (item["id"] % 1000 + 1)
    return 86400 * 3 + (item["id"] * 3571) % (86400 * 300)

# ------------------------------------------------------------------------------------ artwork

_cache = {}
_cache_lock = threading.Lock()


def font(path, size):
    return ImageFont.truetype(path, size)


def palette(key):
    r = h("p", key)
    hue = r % 360
    import colorsys
    a = colorsys.hsv_to_rgb(hue / 360, 0.65, 0.55)
    b = colorsys.hsv_to_rgb(((hue + 40) % 360) / 360, 0.75, 0.18)
    return tuple(int(x * 255) for x in a), tuple(int(x * 255) for x in b)


def gradient(w, hgt, top, bottom):
    img = Image.new("RGB", (1, hgt))
    for y in range(hgt):
        t = y / max(1, hgt - 1)
        img.putpixel((0, y), tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return img.resize((w, hgt))


def real_art(item, kind):
    """The downloaded poster or backdrop of a real film or serial (fetch_art.py), if there is one."""
    path = os.path.join(ART, f"{item.get('key')}-{kind}.jpg")
    if "key" in item and os.path.exists(path):
        with open(path, "rb") as f:
            return f.read()
    return None


def wrap(draw, text, fnt, width):
    words, lines, cur = text.split(), [], ""
    for w in words:
        test = (cur + " " + w).strip()
        if draw.textlength(test, font=fnt) <= width:
            cur = test
        else:
            if cur:
                lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines


def poster(kind, item):
    W, H = 400, 600
    if True:
        a, b = palette(f"{kind}{item['id']}")
        img = gradient(W, H, a, b)
        d = ImageDraw.Draw(img, "RGBA")
        r = h(kind, item["id"])
        for k in range(6):
            cx, cy, rad = (r >> k) % W, (r >> (k + 3)) % H, 60 + (r >> (k + 6)) % 160
            d.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], fill=(255, 255, 255, 18 + k * 4))
        img = img.filter(ImageFilter.GaussianBlur(2))
    over = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    od = ImageDraw.Draw(over)
    for y in range(H // 2, H):
        od.line([(0, y), (W, y)], fill=(0, 0, 0, int(220 * (y - H / 2) / (H / 2))))
    img = Image.alpha_composite(img.convert("RGBA"), over)
    d = ImageDraw.Draw(img)
    f = font(FONT_BOLD, 44)
    lines = wrap(d, item["name"].upper(), f, W - 50)[:3]
    y = H - 60 - 50 * len(lines)
    for ln in lines:
        d.text((25, y), ln, font=f, fill="white")
        y += 50
    d.text((25, H - 50), str(item.get("year", "")), font=font(FONT_REG, 24), fill=(220, 220, 220))
    out = io.BytesIO()
    img.convert("RGB").save(out, "JPEG", quality=82)
    return out.getvalue()


def backdrop(kind, item, w=1280, hgt=720):
    a, b = palette(f"{kind}{item['id']}")
    img = gradient(w, hgt, b, a)
    d = ImageDraw.Draw(img, "RGBA")
    r = h("bd", kind, item["id"])
    for k in range(9):
        cx, cy, rad = (r >> k) % w, (r >> (k + 2)) % hgt, 80 + (r >> (k + 5)) % 300
        d.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], fill=(255, 255, 255, 10 + k * 3))
    img = img.filter(ImageFilter.GaussianBlur(6))
    d = ImageDraw.Draw(img)
    d.text((w - 60, hgt - 80), item["name"].upper(), font=font(FONT_BOLD, 64), fill=(255, 255, 255, 60), anchor="rd")
    out = io.BytesIO()
    img.save(out, "JPEG", quality=80)
    return out.getvalue()


def logo(ch):
    W, H = 320, 180
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    col = ch[6]
    d.rounded_rectangle([10, 30, W - 10, H - 30], radius=22, fill=col)
    name = ch[2].replace(" HD", "")
    size = 52
    while size > 18 and d.textlength(name, font=font(FONT_BOLD, size)) > W - 50:
        size -= 2
    d.text((W / 2, H / 2), name, font=font(FONT_BOLD, size), fill="white", anchor="mm")
    out = io.BytesIO()
    img.save(out, "PNG")
    return out.getvalue()


# ------------------------------------------------------------------------------------ live broadcast

PATTERNS = ["testsrc2=size=1280x720:rate=25", "smptehdbars=size=1280x720:rate=25", "rgbtestsrc=size=1280x720:rate=25"]


# Optional real footage for live channels (make_media.sh fetches an open movie trailer); test patterns otherwise.
FOOTAGE = os.path.join(MEDIA, "showcase.mp4")


class Broadcast:
    """One ffmpeg MPEG-TS stream fanned out to any number of HTTP clients."""

    def __init__(self, pattern, tone, offset=0):
        self.clients = set()
        self.lock = threading.Lock()
        if os.path.exists(FOOTAGE):
            source = ["-stream_loop", "-1", "-ss", str(offset), "-re", "-i", FOOTAGE,
                      "-vf", "scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2", "-r", "25"]
        else:
            source = ["-re", "-f", "lavfi", "-i", pattern, "-f", "lavfi", "-i", f"sine=frequency={tone}:sample_rate=48000"]
        self.cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", *source,
                    "-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency", "-g", "50", "-b:v", "2500k",
                    "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k", "-ac", "2", "-f", "mpegts", "pipe:1"]
        threading.Thread(target=self.run, daemon=True).start()

    def run(self):
        while True:
            p = subprocess.Popen(self.cmd, stdout=subprocess.PIPE)
            while True:
                chunk = p.stdout.read(188 * 64)
                if not chunk:
                    break
                with self.lock:
                    for q in list(self.clients):
                        try:
                            q.put_nowait(chunk)
                        except queue.Full:
                            pass
            time.sleep(1)

    def subscribe(self):
        q = queue.Queue(maxsize=600)
        with self.lock:
            self.clients.add(q)
        return q

    def unsubscribe(self, q):
        with self.lock:
            self.clients.discard(q)


BROADCASTS = []
FLAKY = [0]

# ------------------------------------------------------------------------------------ catch-up archive

from zoneinfo import ZoneInfo

ZONE = ZoneInfo("Europe/London")
ARCHIVE = os.path.join(MEDIA, "archive")
_arch_cache = {}
_arch_lock = threading.Lock()


def _read_ts(b, i):
    return (((b[i] >> 1) & 7) << 30) | (b[i + 1] << 22) | ((b[i + 2] >> 1) << 15) | (b[i + 3] << 7) | (b[i + 4] >> 1)


def _write_ts(b, i, ts):
    b[i] = (b[i] & 0xF0) | (((ts >> 30) & 7) << 1) | 1
    b[i + 1] = (ts >> 22) & 0xFF
    b[i + 2] = (((ts >> 15) & 0x7F) << 1) | 1
    b[i + 3] = (ts >> 7) & 0xFF
    b[i + 4] = ((ts & 0x7F) << 1) | 1


def shift_timestamps(data, offset):
    """MPEG-TS with every PTS/DTS/PCR moved on by `offset` (90 kHz) – a continuous clock across loops."""
    b = bytearray(data)
    wrap = 1 << 33
    for p in range(0, len(b) - 187, 188):
        if b[p] != 0x47:
            continue
        afc = (b[p + 3] >> 4) & 3
        q = p + 4
        if afc & 2:
            alen = b[p + 4]
            if alen >= 7 and b[p + 5] & 0x10:
                base = (b[p + 6] << 25) | (b[p + 7] << 17) | (b[p + 8] << 9) | (b[p + 9] << 1) | (b[p + 10] >> 7)
                base = (base + offset) % wrap
                b[p + 6], b[p + 7], b[p + 8], b[p + 9] = (base >> 25) & 0xFF, (base >> 17) & 0xFF, (base >> 9) & 0xFF, (base >> 1) & 0xFF
                b[p + 10] = ((base & 1) << 7) | (b[p + 10] & 0x7F)
            q = p + 5 + alen
        if afc & 1 and b[p + 1] & 0x40 and q + 19 <= p + 188 and b[q] == 0 and b[q + 1] == 0 and b[q + 2] == 1:
            flags = (b[q + 7] >> 6) & 3
            if flags >= 2:
                _write_ts(b, q + 9, (_read_ts(b, q + 9) + offset) % wrap)
            if flags == 3:
                _write_ts(b, q + 14, (_read_ts(b, q + 14) + offset) % wrap)
    return bytes(b)


def archive_minute(m, restamp=0):
    """The archive file for absolute minute m: the 6-minute loop, its clock running on from loop to loop."""
    files = sorted(f for f in os.listdir(ARCHIVE) if f.endswith(".ts"))
    loop = len(files)
    key = (m, restamp)
    with _arch_lock:
        hit = _arch_cache.get(key)
    if hit is None:
        with open(os.path.join(ARCHIVE, f"m{m % loop}.ts"), "rb") as f:
            raw = f.read()
        hit = shift_timestamps(raw, ((m // loop) * loop * 60 * 90000 + restamp) % (1 << 33))
        with _arch_lock:
            if len(_arch_cache) > 40:
                _arch_cache.clear()
            _arch_cache[key] = hit
    return hit

# ------------------------------------------------------------------------------------ HTTP


def cat_of(item):
    return item["cat"]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def send(self, body, ctype="application/json", code=200):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        elif isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def redirect(self, url):
        self.send_response(302)
        self.send_header("Location", url)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        parts = [p for p in u.path.split("/") if p]
        try:
            if u.path == "/player_api.php":
                return self.api(q)
            if u.path == "/xmltv.php":
                LOG("xmltv requested")
                return self.send(build_xmltv(), "application/xml")
            if u.path.startswith("/img/"):
                return self.image(parts)
            if parts and parts[0] == "live" and len(parts) == 4:
                return self.live(parts[3])
            if parts and parts[0] == "media" and len(parts) == 2:
                return self.serve_file(os.path.join(MEDIA, os.path.basename(parts[1])))
            if parts and parts[0] == "timeshift" and len(parts) == 6:
                return self.timeshift(int(parts[3]), parts[4], int(parts[5].split(".")[0]))
            if parts and parts[0] == "movie" and len(parts) == 4:
                mid = int(parts[3].split(".")[0])
                m = MOVIE_BY_ID.get(mid, {})
                LOG("VOD movie", mid, m.get("name"))
                # A real film plays its copy on Wikimedia Commons (found by fetch_art.py), the rest a test film.
                real = ART_INFO.get(m.get("key"), {}).get("video_url")
                return self.redirect(real or f"{BASE}/media/{'multi.mkv' if mid % 3 == 0 else 'film.mp4'}")
            if parts and parts[0] == "series" and len(parts) == 4:
                eid = int(parts[3].split(".")[0])
                LOG("VOD episode", eid)
                return self.redirect(f"{BASE}/media/film.mp4")
            self.send({"error": "not found"}, code=404)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def serve_file(self, path):
        if not os.path.isfile(path):
            return self.send({"error": "missing"}, code=404)
        size = os.path.getsize(path)
        start, end, code = 0, size - 1, 200
        rng = self.headers.get("Range", "")
        if rng.startswith("bytes="):
            a, _, b = rng[6:].partition("-")
            if a:
                start = int(a)
                end = int(b) if b else size - 1
            elif b:
                start = size - int(b)
            end = min(end, size - 1)
            code = 206
        ctype = {".mp4": "video/mp4", ".mkv": "video/x-matroska", ".ts": "video/mp2t"}.get(os.path.splitext(path)[1], "application/octet-stream")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if code == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            left = end - start + 1
            while left > 0:
                chunk = f.read(min(256 * 1024, left))
                if not chunk:
                    break
                self.wfile.write(chunk)
                left -= len(chunk)

    def live(self, file):
        sid = int(file.split(".")[0])
        LOG("LIVE", sid, CH_BY_ID.get(sid, ("", "", "?"))[2], self.headers.get("User-Agent"))
        if sid == 705:
            return self.redirect("https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8")
        if sid == 803:
            return self.send({"error": "gone"}, code=404)
        if sid == 805:
            # Each connection gets its own encoder with a random timestamp origin, cut after 15 s:
            # what real IPTV servers do when they restart a stream.
            offset = random.randint(60, 3000)
            LOG("RESTART stream, ts offset", offset)
            cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-re", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25",
                   "-f", "lavfi", "-i", "sine=frequency=500:sample_rate=48000", "-c:v", "libx264", "-preset", "veryfast",
                   "-tune", "zerolatency", "-g", "50", "-b:v", "2000k", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k",
                   "-output_ts_offset", str(offset), "-f", "mpegts", "pipe:1"]
            p = subprocess.Popen(cmd, stdout=subprocess.PIPE)
            started = time.time()
            try:
                self.send_response(200)
                self.send_header("Content-Type", "video/mp2t")
                self.send_header("Connection", "close")
                self.end_headers()
                self.close_connection = True
                while time.time() - started < 15:
                    chunk = p.stdout.read(188 * 64)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                LOG("RESTART: dropping connection")
            except Exception:
                pass
            finally:
                p.kill()
            return
        if sid == 802:
            FLAKY[0] += 1
            if FLAKY[0] % 3 != 0:
                LOG("FLAKY -> 503")
                return self.send({"error": "busy"}, code=503)
        b = BROADCASTS[sid % len(BROADCASTS)]
        qu = b.subscribe()
        started = time.time()
        try:
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            while True:
                if sid == 801 and time.time() - started > 15:
                    LOG("DROP connection")
                    return
                if sid == 804 and time.time() - started > 10:
                    LOG("FREEZE (holding connection open, no data)")
                    time.sleep(120)
                    return
                self.wfile.write(qu.get(timeout=10))
        except Exception:
            pass
        finally:
            b.unsubscribe(qu)

    def timeshift(self, duration, start, sid):
        """
        Like an Xtream panel: the archive is one MPEG-TS file per minute, sent back to back. Only complete
        minutes exist (the current one is still being recorded). Normal channels send at 4x real time.
        """
        t0 = int(datetime.strptime(start, "%Y-%m-%d:%H-%M").replace(tzinfo=ZONE).timestamp())
        first = t0 // 60
        last_done = int(time.time()) // 60 - 2  # newest complete minute
        minutes = [m for m in range(first, first + duration) if m <= last_done]
        LOG("TIMESHIFT", sid, datetime.fromtimestamp(t0).strftime("%H:%M"), f"{duration} min ->", f"{len(minutes)} min available")
        if not minutes:
            return self.send({"error": "not archived yet"}, code=404)
        speed = 1.0 if sid == 807 else 4.0
        restamp = random.randint(1, 40000) * 90000 if sid == 808 else 0
        started = time.time()
        sent_s = 0.0
        try:
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            for m in minutes:
                data = archive_minute(m, restamp)
                step = 64 * 1024
                per_byte = 60.0 / speed / len(data)
                for i in range(0, len(data), step):
                    if sid in (806, 808) and time.time() - started > 25:
                        LOG("TIMESHIFT DROP", sid, "mid-stream")
                        return
                    self.wfile.write(data[i:i + step])
                    sent_s += len(data[i:i + step]) * per_byte
                    ahead = sent_s - (time.time() - started)
                    if ahead > 2:
                        time.sleep(ahead - 2)
            LOG("TIMESHIFT done", sid, f"{len(minutes)} min in {time.time() - started:.1f}s")
        except (BrokenPipeError, ConnectionResetError):
            LOG("TIMESHIFT client went away", sid, f"after {time.time() - started:.1f}s")

    def image(self, parts):
        kind, ident = parts[1], parts[2].split(".")[0]
        key = "/".join(parts)
        with _cache_lock:
            hit = _cache.get(key)
        if hit is None:
            if kind == "logo":
                hit = logo(CH_BY_ID[int(ident)])
            elif kind == "poster":
                m = MOVIE_BY_ID[int(ident)]
                hit = real_art(m, "poster") or poster("m", m)
            elif kind == "sposter":
                s = SERIES_BY_ID[int(ident)]
                hit = real_art(s, "poster") or poster("s", s)
            elif kind == "backdrop":
                m = MOVIE_BY_ID[int(ident)]
                hit = real_art(m, "backdrop") or backdrop("m", m)
            elif kind == "sbackdrop":
                s = SERIES_BY_ID[int(ident)]
                hit = real_art(s, "backdrop") or backdrop("s", s)
            elif kind == "still":
                sid, s, e = map(int, ident.split("-"))
                ser = SERIES_BY_ID[sid]
                hit = backdrop("e", dict(id=sid * 1000 + s * 100 + e, name=f"S{s} E{e}"), 640, 360)
            else:
                return self.send({"error": "no"}, code=404)
            with _cache_lock:
                _cache[key] = hit
        self.send(hit, "image/png" if kind == "logo" else "image/jpeg")

    def api(self, q):
        if q.get("username") != USER or q.get("password") != PASS:
            return self.send({"user_info": {"auth": 0}})
        action = q.get("action")
        now = int(time.time())
        if action is None:
            return self.send({
                "user_info": {"username": USER, "password": PASS, "message": "", "auth": 1, "status": "Active",
                              "exp_date": str(now + 200 * 86400), "is_trial": "0", "active_cons": "0",
                              "created_at": str(now - 400 * 86400), "max_connections": "2",
                              "allowed_output_formats": ["m3u8", "ts", "rtmp"]},
                "server_info": {"url": HOST, "port": str(PORT), "https_port": "443", "server_protocol": "http",
                                "rtmp_port": "8880", "timezone": "Europe/London", "timestamp_now": now,
                                "time_now": datetime.now().strftime("%Y-%m-%d %H:%M:%S"), "process": True},
            })
        LOG("api", action, {k: v for k, v in q.items() if k not in ("username", "password", "action")})
        if action == "get_live_categories":
            return self.send([{"category_id": c, "category_name": n, "parent_id": 0} for c, n in LIVE_CATS])
        if action == "get_live_streams":
            return self.send([{
                "num": c[1], "name": c[2], "stream_type": "live", "stream_id": c[0],
                "stream_icon": logo_url(c), "epg_channel_id": c[5] or None,
                "added": str(now - 86400 * 30), "is_adult": "1" if c[3] == "7" else "0", "category_id": c[3],
                "category_ids": [int(c[3])], "custom_sid": "", "tv_archive": 1 if c[4] else 0,
                "direct_source": "", "tv_archive_duration": str(c[4]) if c[4] else 0,
            } for c in CHANNELS])
        if action == "get_vod_categories":
            return self.send([{"category_id": c, "category_name": n, "parent_id": 0} for c, n in VOD_CATS])
        if action == "get_vod_streams":
            return self.send([{
                "num": i + 1, "name": f"EN | {m['name']} ({m['year']})" if m["id"] % 3 == 0 and "key" not in m else m["name"],
                "stream_type": "movie", "stream_id": m["id"],
                "stream_icon": f"{BASE}/img/poster/{m['id']}.jpg",
                "rating": str(m["rating"]) if m["id"] % 5 or "key" in m else "", "rating_5based": round(m["rating"] / 2, 1),
                "added": str(now - added_ago(m)), "is_adult": 0, "category_id": m["cat"],
                "category_ids": [int(m["cat"])], "container_extension": "mkv" if m["id"] % 3 == 0 and "key" not in m else "mp4",
                "custom_sid": None, "direct_source": "",
            } for i, m in enumerate(MOVIES)])
        if action == "get_vod_info":
            m = MOVIE_BY_ID.get(int(q.get("vod_id", 0)))
            if not m:
                return self.send({"info": [], "movie_data": {}})
            return self.send({
                "info": {
                    "kinopoisk_url": "", "tmdb_id": "", "name": m["name"], "o_name": m["name"],
                    "cover_big": f"{BASE}/img/poster/{m['id']}.jpg", "movie_image": f"{BASE}/img/poster/{m['id']}.jpg",
                    "releasedate": m.get("released") or f"{m['year']}-06-14", "episode_run_time": m["runtime"],
                    "youtube_trailer": m["trailer"], "director": m["director"], "actors": m["cast"], "cast": m["cast"],
                    "description": m["plot"], "plot": m["plot"], "age": "" if "key" in m else "12A", "mpaa_rating": "",
                    "rating_count_kinopoisk": 0, "country": m.get("country", "United Kingdom"), "genre": m["genre"],
                    "backdrop_path": [f"{BASE}/img/backdrop/{m['id']}.jpg"],
                    "duration_secs": m["runtime"] * 60, "duration": f"{m['runtime'] // 60:02d}:{m['runtime'] % 60:02d}:00",
                    "video": {"codec_name": "h264", "width": 1920, "height": 1080}, "audio": {"codec_name": "aac", "channels": 2},
                    "bitrate": 4500, "rating": str(m["rating"]),
                },
                "movie_data": {"stream_id": m["id"], "name": m["name"], "added": str(now), "category_id": m["cat"],
                               "container_extension": "mp4", "custom_sid": "", "direct_source": ""},
            })
        if action == "get_series_categories":
            return self.send([{"category_id": c, "category_name": n, "parent_id": 0} for c, n in SERIES_CATS])
        if action == "get_series":
            return self.send([{
                "num": i + 1, "name": s["name"], "series_id": s["id"], "cover": f"{BASE}/img/sposter/{s['id']}.jpg",
                "plot": s["plot"], "cast": s["cast"], "director": s.get("director", ""), "genre": s["genre"],
                "releaseDate": s.get("released") or f"{s['year']}-01-10", "last_modified": str(now - added_ago(s)),
                "rating": str(s["rating"]), "rating_5based": round(s["rating"] / 2, 1),
                "backdrop_path": [f"{BASE}/img/sbackdrop/{s['id']}.jpg"], "youtube_trailer": "", "episode_run_time": "45",
                "category_id": s["cat"], "category_ids": [int(s["cat"])],
            } for i, s in enumerate(SERIES)])
        if action == "get_series_info":
            s = SERIES_BY_ID.get(int(q.get("series_id", 0)))
            if not s:
                return self.send({"seasons": [], "info": [], "episodes": []})
            episodes = {}
            for e, ep in enumerate(s.get("episodes", []), 1):
                # A serial's chapters: one season.
                episodes.setdefault("1", []).append({
                    "id": str(s["id"] * 1000 + 100 + e), "episode_num": e, "title": f"{s['name']} - S01E{e:02d} - {ep['title']}",
                    "container_extension": "mp4", "custom_sid": "", "added": str(now), "season": 1, "direct_source": "",
                    "info": {"movie_image": f"{BASE}/img/sbackdrop/{s['id']}.jpg", "plot": f"Chapter {e} of {len(s['episodes'])}.",
                             "releasedate": s["released"], "rating": s["rating"], "duration_secs": ep["seconds"],
                             "duration": f"{ep['seconds'] // 3600:02d}:{ep['seconds'] // 60 % 60:02d}:{ep['seconds'] % 60:02d}"},
                })
            for season in range(1, 1 if "episodes" in s else s["seasons"] + 1):
                eps = []
                for e in range(1, s["eps"] + 1):
                    eid = s["id"] * 1000 + season * 100 + e
                    r = h("e", eid)
                    eps.append({
                        "id": str(eid), "episode_num": e, "title": f"{s['name']} - S{season:02d}E{e:02d} - {WORDS_A[r % 20]} {WORDS_B[(r >> 4) % 20]}",
                        "container_extension": "mp4", "custom_sid": "", "added": str(now), "season": season, "direct_source": "",
                        "info": {"movie_image": f"{BASE}/img/still/{s['id']}-{season}-{e}.jpg",
                                 "plot": f"{FIRST[r % 20]} faces a difficult choice. {DESC_BITS[r % len(DESC_BITS)]}",
                                 "releasedate": f"{s['year'] + season - 1}-0{1 + e % 9}-1{e % 9}", "rating": 7.4,
                                 "duration_secs": 2580 + r % 900, "duration": "00:45:00"},
                    })
                episodes[str(season)] = eps
            return self.send({
                "seasons": [{"air_date": f"{s['year'] + n - 1}-01-10", "episode_count": s["eps"], "id": n, "name": f"Season {n}",
                             "overview": "", "season_number": n, "cover": f"{BASE}/img/sposter/{s['id']}.jpg"} for n in range(1, s["seasons"] + 1)],
                "info": {"name": s["name"], "cover": f"{BASE}/img/sposter/{s['id']}.jpg", "plot": s["plot"], "cast": s["cast"],
                         "director": s.get("director") or f"{FIRST[s['id'] % 20]} {LAST[(s['id'] >> 2) % 20]}", "genre": s["genre"],
                         "releaseDate": s.get("released") or f"{s['year']}-01-10", "last_modified": str(now), "rating": str(s["rating"]),
                         "backdrop_path": [f"{BASE}/img/sbackdrop/{s['id']}.jpg"], "youtube_trailer": "", "episode_run_time": "45",
                         "category_id": s["cat"]},
                "episodes": episodes,
            })
        if action in ("get_simple_data_table", "get_short_epg"):
            import base64
            sid = int(q.get("stream_id", 0))
            ch = CH_BY_ID.get(sid)
            if not ch:
                return self.send({"epg_listings": []})
            listings = []
            for s, e, title, ep, desc in programmes(ch, now - 86400, now + 2 * 86400):
                listings.append({"id": str(s), "epg_id": "", "title": base64.b64encode(title.encode()).decode(), "lang": "en",
                                 "start": datetime.fromtimestamp(s).strftime("%Y-%m-%d %H:%M:%S"),
                                 "end": datetime.fromtimestamp(e).strftime("%Y-%m-%d %H:%M:%S"),
                                 "description": base64.b64encode(desc.encode()).decode(), "channel_id": "",
                                 "start_timestamp": str(s), "stop_timestamp": str(e), "now_playing": 0, "has_archive": 0})
            return self.send({"epg_listings": listings})
        self.send([])


if __name__ == "__main__":
    for i, p in enumerate(PATTERNS):
        BROADCASTS.append(Broadcast(p, 330 + 110 * i, offset=15 * i))
    LOG(f"Mock Xtream panel on {BASE}  (user {USER} / pass {PASS})")
    if SHOWCASE:
        LOG(f"Showcase: {len(MOVIES)} films and {len(SERIES)} serials" +
            ("" if ART_INFO else " (no artwork downloaded yet: run fetch_art.py)"))
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
