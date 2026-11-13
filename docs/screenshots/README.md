# Screenshots

Captured from the web app with demo data by `pnpm --filter @kodakiko/ui-tests screenshots`
(`tests/ui/screenshots/`). The script starts the UI test stack and generates demo photos and a
video with ImageMagick and ffmpeg. It creates the entries through the web UI, so the thumbnails are
real, and saves the PNGs here. Re-run it after UI changes.

## Phone-sized browser (Pixel 7 viewport)

| Gallery | Viewer | Viewer, note |
|---|---|---|
| ![Gallery](web-mobile-gallery.png) | ![Viewer](web-mobile-viewer.png) | ![Viewer note](web-mobile-viewer-note.png) |

| Calendar | Shared journals | New entry |
|---|---|---|
| ![Calendar](web-mobile-calendar.png) | ![Shared](web-mobile-shared.png) | ![Compose](web-mobile-compose.png) |

| Lock screen |
|---|
| ![Lock](web-mobile-lock.png) |

## Desktop browser (1280 × 800)

![Gallery](web-desktop-gallery.png)
![Viewer](web-desktop-viewer-note.png)
![Calendar](web-desktop-calendar.png)
![Shared journals](web-desktop-shared.png)

Android screenshots will be added with the Android verification round.
