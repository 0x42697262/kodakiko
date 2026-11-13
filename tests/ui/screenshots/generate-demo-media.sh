#!/bin/sh
set -eu
OUT="${1:?output directory}"
mkdir -p "$OUT"
cd "$OUT"
W=1600
H=1200

magick -size ${W}x${H} gradient:'#ffb36b'-'#6a2c91' \
  \( -size ${W}x${H} xc:none -fill '#ffe08a' -draw 'circle 800,560 800,450' -blur 0x6 \) -composite \
  \( -size ${W}x${H} xc:none -fill '#3a1b52' -draw 'polygon 0,1200 0,860 260,720 520,840 800,660 1080,820 1340,700 1600,840 1600,1200' \) -composite \
  \( -size ${W}x${H} xc:none -fill '#22102f' -draw 'polygon 0,1200 0,980 400,880 760,1000 1120,900 1600,1010 1600,1200' \) -composite \
  -quality 88 sunset.jpg

magick \( -size ${W}x560 gradient:'#7cc6ff'-'#e8f6ff' \) \( -size ${W}x640 gradient:'#2a86c4'-'#0a2f4f' \) -append \
  \( -size ${W}x${H} xc:none -fill 'rgba(255,255,255,0.55)' -draw 'ellipse 400,220 180,40 0,360' -draw 'ellipse 1150,160 240,50 0,360' -blur 0x18 \) -composite \
  -quality 88 sea.jpg

magick -seed 7 -size 800x600 plasma:'#2f7d32'-'#0d3b13' -blur 0x3 -resize ${W}x${H}! \
  \( -size ${W}x${H} xc:none -fill 'rgba(255,240,180,0.25)' -draw 'polygon 900,0 1250,0 700,1200 380,1200' -blur 0x40 \) -composite \
  -quality 88 forest.jpg

magick -size ${W}x${H} gradient:'#050a1f'-'#1f2f6b' \
  \( -seed 11 -size ${W}x${H} xc:black +noise Random -colorspace Gray -threshold 99.6% -transparent black \) -composite \
  \( -size ${W}x${H} xc:none -fill '#f4f1d0' -draw 'circle 1250,260 1250,190' \) -composite \
  -quality 88 night.jpg

magick -size 1200x1600 gradient:'#bfe3ff'-'#f9fdff' \
  \( -size 1200x1600 xc:none -fill '#8aa7c4' -draw 'polygon 0,1600 0,900 300,620 520,820 760,520 1200,980 1200,1600' \) -composite \
  \( -size 1200x1600 xc:none -fill '#ffffff' -draw 'polygon 690,600 760,520 830,600 790,640 740,610' \) -composite \
  \( -size 1200x1600 xc:none -fill '#3f5f3a' -draw 'polygon 0,1600 0,1180 400,1080 800,1200 1200,1100 1200,1600' \) -composite \
  -quality 88 mountains.jpg

magick -seed 3 -size 800x600 plasma:'#ff7eb3'-'#ffd86b' -blur 0x2 -resize ${W}x${H}! -modulate 100,120 -quality 88 flowers.jpg

ffmpeg -hide_banner -loglevel error -y -f lavfi -i "mandelbrot=size=1280x720:rate=30" -t 6 \
  -c:v libx264 -pix_fmt yuv420p -movflags +faststart fractal.mp4
