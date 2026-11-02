#!/usr/bin/env python3
"""
Download the mock panel's real artwork from Wikimedia Commons into the git-ignored cache: the posters of the
films and serials in library.json, a still from each film, and the address of each film's playable copy on
Commons. Everything used is in the public domain or under a Creative Commons licence.

  python3 -I tools/mock-xtream/fetch_art.py [--credits FILE.md]

Writes cache/media/art/<key>-poster.jpg, <key>-backdrop.jpg and art.json. What's already there is kept, so
running it again only fetches what's missing. --credits also writes the attribution list for the artwork
(docs/screenshots/CREDITS.md is made this way).
"""
import io
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

from PIL import Image, ImageEnhance, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "cache", "media", "art")
API = "https://commons.wikimedia.org/w/api.php"
# Wikimedia asks for a User-Agent that says who's asking.
UA = "NimbusMockPanel/1.0 (https://github.com/lucasclgibson/nimbus)"


def get(url, tries=4):
    for attempt in range(tries):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=120).read()
        except Exception:
            if attempt == tries - 1:
                raise
            time.sleep(3 * (attempt + 1))


def query(**params):
    q = urllib.parse.urlencode(dict(action="query", format="json", formatversion=2, **params))
    return json.loads(get(f"{API}?{q}"))["query"]["pages"][0]


def plain(html):
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", html or "")).strip()


def image_info(name, width, seek=None):
    """A file's description and a rendered copy [width] wide (for a video: the frame at [seek] seconds)."""
    params = dict(prop="imageinfo", titles="File:" + name, iiprop="url|extmetadata", iiurlwidth=width,
                  iiextmetadatafilter="Artist|LicenseShortName|LicenseUrl")
    if seek is not None:
        params["iiurlparam"] = f"seek={seek}"
    page = query(**params)
    info = page["imageinfo"][0]
    meta = {k: v.get("value", "") for k, v in info.get("extmetadata", {}).items()}
    credit = dict(file=page["title"][5:], page=info["descriptionurl"], author=plain(meta.get("Artist")) or "Unknown",
                  licence=plain(meta.get("LicenseShortName")) or "Unknown", licence_url=meta.get("LicenseUrl", ""))
    return info.get("thumburl") or info["url"], credit


def trim_bars(im):
    """Crop the black bars some copies have baked in (a 4:3 picture inside a 16:9 frame)."""
    box = im.convert("L").point(lambda v: 255 if v > 18 else 0).getbbox()
    if box and box[2] - box[0] > im.width / 2 and box[3] - box[1] > im.height / 2:
        return im.crop(box)
    return im


def cover(im, w, h, anchor=0.4):
    """Scale and crop to exactly w x h. [anchor] places the crop vertically: 0 keeps the top, 1 the bottom."""
    k = max(w / im.width, h / im.height)
    im = im.resize((max(w, round(im.width * k)), max(h, round(im.height * k))), Image.LANCZOS)
    x, y = (im.width - w) // 2, round((im.height - h) * anchor)
    return im.crop((x, y, x + w, y + h))


def save(im, path):
    im.convert("RGB").save(path, "JPEG", quality=86, optimize=True)


def fetch(item, entry):
    key = item["key"]
    poster = os.path.join(OUT, f"{key}-poster.jpg")
    if not os.path.exists(poster) or "poster" not in entry:
        url, entry["poster"] = image_info(item["poster"], 600)
        im = Image.open(io.BytesIO(get(url)))
        if im.mode in ("RGBA", "LA", "P"):
            im = im.convert("RGBA")
            im = Image.alpha_composite(Image.new("RGBA", im.size, "black"), im)
        save(im, poster)
    backdrop = os.path.join(OUT, f"{key}-backdrop.jpg")
    if item.get("video") and item.get("still") is not None:
        if not os.path.exists(backdrop) or "still" not in entry:
            url, entry["still"] = image_info(item["video"], 1920, seek=item["still"])
            save(cover(trim_bars(Image.open(io.BytesIO(get(url))).convert("RGB")), 1280, 720), backdrop)
        if "video_url" not in entry:
            info = query(prop="videoinfo", titles="File:" + item["video"], viprop="url|size|derivatives")["videoinfo"][0]
            by_key = {d["transcodekey"]: d["src"] for d in info.get("derivatives", [])}
            # Only a copy of the whole film plays (some stills come from clips).
            whole = (info.get("duration") or 0) >= item.get("runtime", 0) * 60 * 0.6
            entry["video_url"] = (by_key.get("720p.vp9.webm") or by_key.get("480p.vp9.webm") or info["url"]) if whole else None
    elif not os.path.exists(backdrop):
        # No still: the poster blurred and dimmed, as streaming apps do.
        im = cover(Image.open(poster).convert("RGB"), 1280, 720, anchor=0.3).filter(ImageFilter.GaussianBlur(24))
        save(ImageEnhance.Brightness(im).enhance(0.55), backdrop)


def tidy(author):
    """Commons' author field, shortened for a table: no footnote marks, prefixes or repeats."""
    a = re.sub(r"\[\d+\]", "", author).replace("Creator:", "").split(";")[0].strip()
    half = len(a) // 2
    if len(a) % 2 == 0 and a[:half] == a[half:]:
        a = a[:half]
    a = a if len(a) <= 80 else a[:77] + "…"
    return a.replace("|", "\\|")


def link(c):
    licence = f"[{c['licence']}]({c['licence_url']})" if c["licence_url"] else c["licence"]
    page = c["page"].replace("(", "%28").replace(")", "%29")
    return f"[{tidy(c['author'])}]({page}), {licence}"


def write_credits(lib, art, path):
    lines = [
        "# Screenshot credits",
        "",
        "The screenshots in the README show Nimbus playing the test panel in `tools/mock-xtream` in showcase mode. Its",
        "channels and programmes are made up; its films and serials are real: open movies released under Creative",
        "Commons licences and classics in the public domain. Their posters and stills come from",
        "[Wikimedia Commons](https://commons.wikimedia.org/), credited below as Commons records them. Film facts and",
        "ratings come from [TMDB](https://www.themoviedb.org/).",
        "",
        "The live TV footage is the [Sintel](https://durian.blender.org/) trailer, © Blender Foundation,",
        "[CC BY 3.0](https://creativecommons.org/licenses/by/3.0/).",
        "",
        "| Title | Poster | Still |",
        "| --- | --- | --- |",
    ]
    for item in lib["films"] + lib["series"]:
        e = art.get(item["key"], {})
        if "poster" not in e:
            continue
        still = link(e["still"]) if "still" in e else "(none)"
        lines.append(f"| {item['title']} ({item['year']}) | {link(e['poster'])} | {still} |")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def main():
    lib = json.load(open(os.path.join(HERE, "library.json"), encoding="utf-8"))
    os.makedirs(OUT, exist_ok=True)
    art_path = os.path.join(OUT, "art.json")
    art = json.load(open(art_path)) if os.path.exists(art_path) else {}
    failed = 0
    for item in lib["films"] + lib["series"]:
        try:
            fetch(item, art.setdefault(item["key"], {}))
            print(f"  {item['title']}", flush=True)
        except Exception as e:
            failed += 1
            print(f"  {item['title']}: {e}", flush=True)
        with open(art_path, "w") as f:
            json.dump(art, f, indent=1)
        time.sleep(0.2)
    if "--credits" in sys.argv:
        write_credits(lib, art, sys.argv[sys.argv.index("--credits") + 1])
    print(f"artwork in {OUT}" + (f" ({failed} failed: run again to retry)" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
