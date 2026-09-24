# Selfie Screen

Open-source replacement for the vectorflow "CapAR" app that drives the magnetic
rear-camera selfie monitor (Bluetrum BLE firmware). It streams your camera to the
little screen, with Samsung-style zoom including the 0.6x ultra-wide lens.

## Features
- CameraX preview with zoom chips: 0.6 (ultra-wide, if the phone exposes it) / 1 / 2 / 3 / 5 / 10
- Pinch to zoom
- Streams JPEG frames to the screen over BLE using the same protocol as the official app
- The screen's buttons work: shutter = photo, flip = switch camera, zoom up/down = step through chips
- Mirror toggle for the output, front/back camera flip
- Splash screen: pick any image in the app (**Splash** button); it shows on the screen for 3 s
  every time it connects, and can be shown on demand
- On-screen menu driven by the screen's buttons: **double-press FLIP** to open it, then
  FLIP / ZOOM+ = next, ZOOM- = previous, SHUTTER = select. Switch photo/video, front/back
  camera, flash, mirror, or show the splash. Closes after 10 s idle.
- No account, no cloud binding

## Hardware
- **Product:** [Rear Camera Selfie Device (AliExpress)](https://www.aliexpress.com/item/1005012363706535.html):
  magnetic screen that clips to the back of the phone so you can frame shots with the rear camera
- **Official app:** [Capar by vectorflow on Google Play](https://play.google.com/store/apps/details?id=cn.vectorflow.capar)
  (`cn.vectorflow.capar`). vectorflow doesn't publish the APK anywhere else; third-party APK
  mirrors are unofficial.
- What the screen reports over BLE:
  - Advertises as `CAMERA_PRO`
  - Resolution 160×192, rotation 0
  - Frame buffer 15360 bytes (each JPEG must be ≤ 15260)
  - Negotiates an MTU of 498 on Windows
  - Accepts roughly 3 frames per second
- The USB-C port is **charge-only**: it doesn't enumerate on a PC, so the firmware can't be
  reflashed over the cable. Everything, including the splash and the menu, is drawn by the phone and
  streamed as frames.
- The screen also sends `AA 55 07` and `AA 55 08` around button presses. They're not decoded yet
  (possibly button release or long press).

## Build (no Android Studio needed)
The workflow in `.github/workflows/build.yml` builds a debug APK on every push.
Download it from the Actions run → Artifacts → `SelfieScreen-apk`, then install it
on your phone (allow "install unknown apps").

Or open the folder in Android Studio and run it directly.

## Protocol summary
- Nordic UART service `6e400001-…`; write `…0002`, notify `…0003`; MTU 500
- Every BLE packet = chunk + trailing flag byte (1 = more, 0 = last)
- Commands are prefixed `AA 55`:
  - phone → dev `F4 01` after connect ("Android")
  - dev → phone `01 w:u16 h:u16 rot:u8 buf:u32` (little endian); phone replies `01 01`
  - dev → phone `03` = send a frame; phone replies with a raw JPEG ≤ buf-100 bytes (no header)
  - dev → phone `02` shutter, `06` flip, `0A`/`0B` zoom down/up, `00` close, `F7 mtu:u16`
