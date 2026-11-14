# Capture and import

How photos and videos get into Kodakiko: the camera, the gallery picker, files, paste and drop,
and the import pipeline that encrypts them. Code: `apps/client/src/features/capture/`,
`apps/client/src/adapters/capture/`, `apps/client/src/adapters/media/`,
`apps/client/src/services/media/import-service.ts`, `packages/core/src/media/`.

## The capture screen (`/capture`)

Opened from the gallery's round button (a new entry) or from the edit page's photo, video and
add buttons (`/capture?entryId=…&mode=photo|video`, adding to that entry). It is a full-screen
modal; on Android it slides up from the bottom.

**Android camera** (`react-native-vision-camera`):

- Permission gate: without camera permission the screen explains why and offers **Allow Camera**,
  or **Open Settings** when Android will not ask again. The microphone is asked for on the first
  video.
- A **Photo / Video** switch under the shutter. In Photo mode a tap takes a JPEG (quality 0.92, no
  shutter sound). In Video mode a tap starts recording an MP4 with sound and a second tap stops
  it; a red ring and an `mm:ss` timer show while recording. A tap recording has no length limit;
  its file size is capped so the encrypted file still fits the server's upload limit.
- **Hold to record** (Capture settings, default on): holding the shutter longer than 300 ms records
  in either mode and releasing stops; the clip is capped at **Maximum hold-to-record length**
  (5, 10, 15, 30, 60 or 120 s; default 15 s).
- Flip (front/back camera), and haptic ticks on the shutter and on recording start and stop.
- **Camera app** = *Phone's camera app* hands capture to the phone's own camera
  (`launchCameraAsync`); its result joins the tray. Some camera apps also keep their own,
  unencrypted copy in the phone's gallery; the setting says so.
- **Keep the screen on while capturing** (default on) and **Start with camera** (back or front
  camera first; default back).

**Web camera** (when `getUserMedia` exists): a live `<video>` view; tap grabs a frame as JPEG;
video uses `MediaRecorder` with the first supported of MP4 (H.264/AAC), WebM VP9/Opus or WebM.
Without a camera the view says so and points to the file picker.

**Other sources:**

| Source | Android | Web |
|---|---|---|
| Gallery / files | Android Photo Picker (`expo-image-picker`, several at once, EXIF included, original file unchanged) | File input for JPEG, PNG, WebP, HEIC/HEIF, MP4, MOV, WebM |
| Drag and drop | — | Drop anywhere on the screen (highlighted while dragging) |
| Paste | — (paste is on the edit page) | Ctrl+V while the screen is open |

**The tray** at the bottom collects items as thumbnails, each with a remove button and a spinner
while it imports. **Next** (new entry) creates the entry and opens the edit page; **Add to Entry**
(from the edit page) adds the items and returns. Both are disabled while nothing is in the tray or
a video is recording; Add to Entry also waits for imports to finish. **Close** asks "Discard N
items?" when the tray is not empty.

The tray lives in memory. It is cleared, with its files deleted, on lock and sign-out; an import
that finishes after a clear deletes its own files instead of joining the tray.

## Refusals

Each refused item is named in one banner after a batch:

| Reason | Message |
|---|---|
| Not a photo or video Kodakiko reads | "*name* isn't a supported photo or video." |
| The browser cannot decode it (HEIC, HEVC on most desktop browsers) | "This browser can't read *name*. Add it from the Android app instead." |
| Android cannot decode it | "*name* couldn't be opened." |
| Larger than the server allows | "*name* is larger than this server allows." |
| The entry is full (`maxMediaPerEntry`, default 20) | "An entry can hold up to *max* photos and videos." |
| Anything else went wrong | "*name* couldn't be added. Try again." |

Camera items have no file name; messages say "This photo" or "This video".

## The import pipeline

`importOne(input, slots)` runs cheap and local checks first:

1. Refuse when the entry is full.
2. Sniff the first 64 bytes: JPEG, PNG, WebP, HEIF/HEIC (brands `heic heix heim heis hevc hevx
   mif1 msf1`, checked before MP4), MOV (`qt  `), MP4 (`isom iso2 iso4 iso5 iso6 mp41 mp42 avc1
   M4V  dash 3gp4 3gp5 3g2a`), WebM. Anything else is refused.
3. Probe with the platform decoder (size and duration); a file it cannot decode is refused. Sizes
   are as displayed: EXIF orientation is already applied, and videos rotated 90° or 270° in their
   track matrix get width and height swapped.
4. Refuse when the encrypted size would exceed the server's `maxUploadBytes`.
5. Read metadata: EXIF from JPEG, PNG and WebP (date, offset, GPS, orientation); the picker's EXIF
   for HEIF and to fill gaps; MP4/MOV `mvhd` creation time and `©xyz` or Apple ISO 6709 location;
   nothing for WebM. Camera captures ignore embedded time and GPS.
6. Capture time, first that exists: EXIF date (with its offset, else the device's) → container
   creation time → file modified time → now.
7. Thumbnail: WebP, longest edge 512, quality 80; videos use a frame at min(1 s, half the length).
8. New random ids for the media and its two objects, a random media key, and the original and
   thumbnail keys derived from it.
9. Encrypt the original and the thumbnail in the STREAM format chunk by chunk into the local file
   store, yielding to the UI between chunks. On Android, unless **Encrypt thumbnails** is on, a
   plain copy of the thumbnail is also kept in app-private storage for fast scrolling.
10. Delete the plaintext input and wipe the derived keys; return the media descriptor.

Any failure after step 8 deletes what was written and reports "couldn't be added". All metadata
readers are bounded and never throw on malformed files; they return what they could read.

Voice notes use `importVoiceNote`, which skips probing and thumbnail rendering (see
[edit-page.md](edit-page.md)). Crops and pasted pictures go through `importOne`.

## Place

If **Add location to new photos** is on, the camera asks for the device position once per capture
session (5 s timeout). The entry's place is the earliest item's EXIF location, else that device
position.

## Plaintext temp files

Android writes camera files, picker copies and manipulator output to the app cache. All of
`Paths.cache` is deleted at app start, on lock and on sign-out, and files of imports abandoned by
a killed app are swept at unlock. The web never writes plaintext to storage; it keeps media in
memory `Blob`s.

## Checked only on a phone

Camera capture, hold to record, haptics, the phone's camera app and the Photo Picker.
