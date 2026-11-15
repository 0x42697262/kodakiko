#!/bin/sh
set -eu

DIRECTORY=$(cd "$(dirname "$0")" && pwd)
MIN_MAJOR=8

if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "ffmpeg is missing. Install ffmpeg ${MIN_MAJOR}.0 or newer and run again." >&2
  exit 1
fi

VERSION=$(ffmpeg -version | head -n 1 | sed -n 's/^ffmpeg version [^0-9]*\([0-9][0-9]*\).*/\1/p')
if [ -z "$VERSION" ] || [ "$VERSION" -lt "$MIN_MAJOR" ]; then
  echo "ffmpeg ${MIN_MAJOR}.0 or newer is required." >&2
  exit 1
fi

cd "$DIRECTORY"
RUN="ffmpeg -hide_banner -loglevel error -y"

$RUN -f lavfi -i testsrc2=size=2000x1500 -frames:v 1 -q:v 3 photo-gps-base.jpg
$RUN -f lavfi -i testsrc2=size=1200x1600 -frames:v 1 -q:v 3 photo-portrait.jpg
$RUN -f lavfi -i testsrc2=size=800x600 -frames:v 1 screenshot.png
$RUN -f lavfi -i testsrc2=size=1280x720:rate=30 -f lavfi -i sine=frequency=440 -t 20 \
  -c:v libx264 -pix_fmt yuv420p -b:v 2500k -c:a aac -movflags +faststart video-20s.mp4
$RUN -f lavfi -i testsrc2=size=640x480:rate=30 -t 3 -c:v libx264 -pix_fmt yuv420p -f mov \
  -metadata location=+48.8584+002.2945/ -metadata creation_time=2026-05-01T08:00:00Z video-gps.mov
$RUN -f lavfi -i testsrc2=size=320x240:rate=30 -t 2 -c:v libvpx-vp9 -b:v 300k video-short.webm
$RUN -f lavfi -i sine=frequency=330 -t 2 -c:a libopus -b:a 48k voice-opus.webm

sha256sum photo-gps-base.jpg photo-portrait.jpg screenshot.png video-20s.mp4 video-gps.mov \
  video-short.webm voice-opus.webm > SHA256SUMS
