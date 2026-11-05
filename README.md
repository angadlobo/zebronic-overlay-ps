# Pad Overlay

Use a third-party Bluetooth gamepad (made for a **Zebronics Max Link**, but any Android gamepad works) with **PS Remote Play** on Android.

Remote Play only accepts official DualShock/DualSense controllers. Pad Overlay sits on top of Remote Play
and turns each controller button into a tap on Remote Play's own on-screen button. Sticks become drags.

## PS controller mode (recommended)
Your gamepad appears to Remote Play as a DualSense, with no overlay or fake touches, so every button is a real button. It needs no root, but it does need adb once after each phone restart, because only the adb shell may create the virtual device.

1. Connect the gamepad to the phone over USB (Bluetooth isn't working yet), install the APK, and turn on USB debugging.
2. Plug the phone into your computer and run `scripts/pad-bridge.sh`. You can unplug afterwards.
3. Open Remote Play and play. To stop, run `scripts/pad-bridge.sh stop`.

PS, Options and Create are pressed as touches on Remote Play's own on-screen buttons (the app brings them back if Remote Play has hidden them). Some pads, like the Zebronics, never send a PS button at all. To choose which pad button does each one, hold the 🎮 bubble, tap its ✛, tap **Bind**, press the pad button, then **Save ✓**.

The 🎮 bubble stays on screen: tap it to turn the PS controller on or off without the computer. The rest of the overlay stays out of the way while this is running. Rumble and motion aren't passed through. This changes how Remote Play sees your controller, so check you're comfortable with Sony's terms of use.

## How the overlay works
- An **Accessibility service** reads your gamepad buttons and performs touch gestures (multi-finger, so you can hold a stick and press ✕ at the same time).
- A **mapping overlay** lets you drag an amber ✛ onto each Remote Play button and bind a controller button to it.
- **Press any button on the controller** and the overlay hides itself, saves, and you're playing.
- **Floating 🎮 bubble:** tap to turn Pad Overlay on or off (green = on, grey with a red slash = off), hold to bring the mapping overlay back, drag to move it. There's the same on/off switch in the app.
- Optional: on-screen touch buttons you can move and resize anywhere (Settings → "Also show on-screen touch buttons").

## Build the APK
**Option A: Direct download.** Open https://github.com/angadlobo/zebronic-overlay-ps/releases/latest/download/PadOverlay.apk on your phone. Every push to `main` rebuilds it.

**Option B: Android Studio.** Open the folder and choose Build → Build APK(s).

## Setup on the phone
1. Pair the Zebronics gamepad over Bluetooth. If it has modes, use **Android / X-input** mode, not PC/D-input.
2. Install the APK and open **Pad Overlay**.
3. Tap **"Turn on Pad Overlay in Accessibility"** and enable it.
   On Android 13+ the toggle may be greyed out. Fix: App info → ⋮ → **Allow restricted settings**, then try again.
4. Tap **"Open Remote Play and map buttons"** and start a Remote Play session so its on-screen controller is visible.
5. Drag each amber ✛ onto the matching Remote Play button. For sticks, put the ✛ on the stick centre and use −/+ to set how far it pushes.
6. Defaults already follow the standard Android gamepad layout (A=✕, B=○, X=□, Y=△, …). To change one: tap its ✛ → **Bind** → press the controller button.
7. Press any controller button. The overlay disappears and you play.

## Notes
- **Keep Remote Play's on-screen controller visible.** We tap its buttons, so if they're hidden nothing happens.
- Sticks, analog triggers and hat-style D-pads need the option "Read sticks, triggers and D-pad hat". It uses an invisible 1-pixel window that holds input focus, because Android only sends analog input to the focused window.
- If the right stick behaves oddly, toggle "Right stick uses RX/RY axes".
- On-screen touch buttons must not sit on top of a ✛ target, or they block the tap. They show **red** in the editor when they do.
- Latency is roughly one frame (~8–16 ms) on top of Remote Play's own.
