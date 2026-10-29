# Driver Logger v2.1

Same package name and app as v1 (com.example.driverlogger), so it installs over the old one.
If the signing key differs from the one used for v1/v2, Android will refuse to update: export your
old captures first, uninstall the old version, then install this one.

## What changed from v1
- Includes accessibility nodes Uber marks "not important" (v1 dropped them). Such nodes are tagged
  `notimportant` in the text captures.
- Also listens for "window added/removed" events, so overlay popups can trigger a capture.
- Each text capture ends with `others=pkg:Llayer:ttype` listing windows from apps that are NOT
  watched, and `noroot:` for windows that expose no content. This shows where an offer popup lives.
- Blank (secure-screen) screenshots are noted in events.log as "looks BLANK".
- Rolling storage: oldest files are deleted past 8000 text / 4000 screenshots instead of stopping.

## Screenshots: only the useful ones are kept (v2.1)
A screenshot is taken about every 1.2 s into a temporary "pending" folder. It is deleted after
about 20 s unless something interesting happens, in which case the previous 10 s of shots are kept
and new shots are kept for the next 15 s. "Interesting" means any of:
- a notification from a watched app
- offer-like text on screen (Accept / Decline / Reject ride / est. rate / reservation request /
  request matching / Matched to trip / exclusive / scheduled ride)
- the ride state changes (Finding trips, Picking up, Waiting for rider, Dropping off, online/offline)
- a new real window from a watched app appears or disappears (popups, app switches)
- a big change on screen while a watched app is present (this catches offers whose text is hidden)

Near-identical screenshots are never stored. Look in events.log for lines starting with `KEEP (`:
they say why a clip was kept. What it cannot do: judge the picture itself. An offer that has no
readable text, no notification, no window change and no watched-app window present would be missed.

## Build
Open the folder in Android Studio (or build with your existing setup): `assembleDebug`.
AGP 8.5.2, Gradle 8.7, JDK 17, compileSdk 34, minSdk 30. No libraries needed.
The gradle wrapper jar is not included; Android Studio or your Gradle install provides it.

## Use
1. Install, open Driver Logger, switch it on in Accessibility settings.
2. Drive a normal shift. Try to include live Uber offers of both kinds.
3. Export (zip with screenshots) and send it back. Note the rough time of a couple of live offers.
