#!/usr/bin/env bash
# Generate the mock panel's test media (git-ignored): a film (MP4), a multi-audio/subtitle MKV, a catch-up TS
# and a catch-up archive (one MPEG-TS file per minute, as Xtream panels store it).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
D="$HERE/cache/media"; mkdir -p "$D"; cd "$D"
printf '1\n00:00:01,000 --> 00:00:06,000\nNimbus subtitle test\n\n2\n00:00:08,000 --> 00:00:14,000\nIf you can read this, subtitles work.\n' > subs.srt
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "testsrc2=size=1920x1080:rate=25" -f lavfi -i "sine=frequency=440:sample_rate=48000" \
  -t 180 -c:v libx264 -preset veryfast -b:v 3000k -pix_fmt yuv420p -g 50 -c:a aac -b:a 160k -movflags +faststart film.mp4
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "smptehdbars=size=1920x1080:rate=25" -f lavfi -i "sine=frequency=330:sample_rate=48000" \
  -f lavfi -i "sine=frequency=660:sample_rate=48000" -i subs.srt -t 120 -map 0:v -map 1:a -map 2:a -map 3:s \
  -c:v libx264 -preset veryfast -b:v 2500k -pix_fmt yuv420p -g 50 -c:a:0 aac -b:a:0 160k -c:a:1 ac3 -ac:a:1 6 -b:a:1 384k -c:s srt \
  -metadata:s:a:0 language=eng -metadata:s:a:0 title="English" -metadata:s:a:1 language=fre -metadata:s:a:1 title="Français 5.1" \
  -metadata:s:s:0 language=eng multi.mkv
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "rgbtestsrc=size=1280x720:rate=25" -f lavfi -i "sine=frequency=550:sample_rate=48000" \
  -t 240 -c:v libx264 -preset veryfast -b:v 2000k -pix_fmt yuv420p -g 50 -c:a aac -b:a 128k -f mpegts catchup.ts
# Real footage for live channels and catch-up (nicer for screenshots than test patterns): the Sintel trailer,
# (c) Blender Foundation, CC BY 3.0 (https://durian.blender.org). Optional: skipped if the download fails.
[ -f showcase.mp4 ] || curl -fsSL -o showcase.mp4 https://download.blender.org/durian/trailer/sintel_trailer-1080p.mp4 || rm -f showcase.mp4
# Real posters and stills of the films and serials in library.json, from Wikimedia Commons. Optional too: the
# panel draws its own artwork for anything missing.
python3 -I "$HERE/fetch_art.py" || echo "some artwork didn't download: run fetch_art.py again to retry"

# Archive: 6 minutes, one file per minute, each written by its own muxer (so continuity counters restart at
# every join, like a real panel's archive). Test pattern: the picture shows seconds since the start of the loop.
mkdir -p archive
if [ -f showcase.mp4 ]; then
  SRC=(-stream_loop -1 -i showcase.mp4 -vf "scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2" -r 25 -ac 2)
else
  SRC=(-f lavfi -i "testsrc=size=1280x720:rate=25" -f lavfi -i "sine=frequency=440:sample_rate=48000:beep_factor=4")
fi
ffmpeg -hide_banner -loglevel error -y "${SRC[@]}" \
  -t 360 -c:v libx264 -preset veryfast -b:v 1500k -maxrate 1800k -bufsize 3000k -pix_fmt yuv420p -g 50 -keyint_min 50 -sc_threshold 0 \
  -c:a aac -b:a 128k -f segment -segment_time 60 -segment_format mpegts -reset_timestamps 0 archive/m%d.ts
echo "media ready in $D"
