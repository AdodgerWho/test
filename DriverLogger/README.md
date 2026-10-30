# Driver Logger v3.3

Same package name as v1 to v2.1 (com.example.driverlogger), so it installs over the old one if the
signing key matches. If Android refuses to update: export first, uninstall, then install this one.

## What's new in v3.3
- **hich goes online by itself.** Scheduled rides can only be accepted while hich is online. When the hich
  home screen is open, says "You're offline" and shows the big green "GO" button, the app taps GO once.
  - Only an enabled button whose text is exactly "GO" is tapped.
  - The offline screen has to stay up for 1.5 seconds first, so a screen that is still loading is left alone.
  - It will not tap again for 10 minutes, so going offline on purpose right afterwards sticks.
  - Never while a live hich offer is on screen. It only acts when hich is actually open on screen.
  - On by default. Untick "Go online in hich automatically..." in Settings to stop it.
  - events.log gets a `HICH ONLINE:` line for each tap, with the click result.

## What's new in v3.2
- **hich scheduled rides open by themselves.** On the hich home screen, the car-and-clock icon at the top
  right has a red badge with the number of waiting scheduled rides. When that number is 1 or more, the app
  taps the icon once, which opens Schedule Bookings on the OFFERS tab.
  - The badge is found by its number and its position (top right), since the screenshots don't show the
    button's internal id. This is a best guess: check events.log after the first shift.
  - It does not tap again for the same number within 10 minutes, so backing out of the screen does not
    reopen it. A new ride (a different number) taps again.
  - Never taps while a live hich offer is on screen, and not while Schedule Bookings is already open.
  - On by default. Untick "Open hich scheduled rides automatically..." in Settings to stop it.
  - events.log gets a `HICH SCHEDULED:` line for each tap, and a `PRIORITY MODE:` note if it finds a badge
    but nothing around it to tap.
- It only opens the screen. It does not accept the ride (accepting is not built yet).

## What's new in v3.1
- **Lyft Priority Mode turns on by itself.** When Lyft shows the "Turn on" pill next to "Priority Mode"
  (it only appears while Priority Mode is available and off), the app taps it once. When Priority Mode
  is already on, the pill is gone (it just says "On"), so there is nothing to tap.
  - Only an enabled button whose text is exactly "Turn on", inside the Priority Mode row of the Lyft
    driver app, is ever tapped. Nothing else is tapped.
  - Never taps while an offer card is on screen. Waits 6 seconds between tries.
  - On by default. Untick "Turn on Lyft Priority Mode automatically..." in Settings to stop it.
  - events.log gets a `PRIORITY MODE:` line for every tap (with the click result) and, at most every
    30 s, a line if it found the pill but could not tap it. Send those back after the first shift.
- This is the first version that taps anything. Offers are still only alerted, never accepted.

## What's new in v3.0
- **Reads offers**, not just logs them.
  - Lyft: price, the "$/hr est. rate" line, pickup and trip minutes/miles (in-app card and the
    background floating card).
  - hich: price, minutes/miles to pickup, trip length (live offers). Scheduled-ride offers are
    flagged by the "Schedule Bookings" screen and judged on price.
  - Uber reservation requests: price, trip length, pickup time. Rate = price / trip hours.
  - Uber live offers: the card has no readable text, so the app crops the offer card out of a
    screenshot and reads it with on-device OCR (Google ML Kit, works offline). Rate =
    price / ((pickup min + trip min) / 60). This ignores the drive back after drop-off.
- **Two modes** (main screen): *Log only* records quietly; *Alert only* also pulses the screen green
  (4 pulses over about 2 s, see-through, taps pass straight through) with a small "Uber $9.03 · $36/hr"
  pill, plus optional vibration and a short beep. The app never taps anything.
- **Good offer** = estimated $/hr at or above your setting (default $32). hich live rides: pickup
  within your mileage (default 5). hich scheduled rides: price at or above your setting (default $20).
- **Dark, quiet UI.** The service shows nothing on screen except the alert pulse. Open the app for the
  offer list, today's stats, settings, setup and export. Tap an offer to see what was read.
- Smaller exports: the screenshot buffer is shorter (8 s before / 10 s after) and the rolling caps are
  lower (4000 text, 1500 screenshots). Offer history (offers.jsonl) is included in exports.

## Build
Android Studio, `assembleDebug`. AGP 8.5.2, Gradle 8.7, JDK 17, compileSdk 34, minSdk 30.
One new dependency: `com.google.mlkit:text-recognition:16.0.0` (needs internet on the first Gradle sync).

## First shift
1. Install, open the app, turn the service on in Accessibility settings.
2. Leave it in *Log only* for the first shift, then check the offer list against what you actually saw.
   In particular check Uber: tap an Uber row to see the OCR text it read. Rows marked UNREAD mean the
   text could not be parsed.
3. When Uber numbers look right, switch to *Alert only*.
4. Export and send the zip back, with the rough time of a couple of offers.

## Not in this version
Auto-accept of offers, going offline on the other app, Uber selfie handling.
