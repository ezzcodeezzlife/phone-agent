# Test results (2026-11-17, emulator)

Setup:
- Device: Pixel 9 emulator, Android 15 (API 35, Google Play image).
- Model: `gpt-6.1-terra`, reasoning effort `low`, through *Sign in with ChatGPT* (ChatGPT Plus/Pro plan, no API key).
- Every run is in `docs/test-runs/<time>-<mode>/`: one screenshot plus one tree file per step, `log.txt` and `summary.json`.
- The tasks were given in German and the app's UI was German at the time. `log.txt` and `summary.json` were translated to English afterwards. Screenshots and tree files are raw captures, so German websites and apps (Google search, the WebView test app) appear in German there.

## Overview

| # | Task | Mode | Result | Steps | Time | Note |
|---|---|---|---|---|---|---|
| 1 | Read the newest email in Gmail | tree | ✗ (correctly stopped) | 4 | 25 s | The emulator's Google test account doesn't sync; the agent tapped "Sync now" and reported it honestly. |
| 2 | Calendar event tomorrow 15:00 | tree | ✗ (correctly stopped) | 6 | 26 s | "No calendars have been synchronized": same environment problem. Navigation up to "New event" worked. |
| 3 | Calendar event tomorrow 16:00 | screenshot | ✗ (correctly stopped) | 4 | 17 s | Same as #2. The saved log stops after step 1; the rest was lost in an emulator crash. |
| 4 | Alarm 7:30 "Agent Test tree" (Do Not Disturb on) | tree (v1) | ≈✓, hit the 30-step limit | 30 | 138 s | DND blocked "Add alarm". The agent spent ~18 steps going back and forth (safety rule too strict), then set the alarm correctly. Time dial 7 → 30 → AM tapped by element ID. |
| 5 | Alarm 7:30 "Agent Test screenshot" (DND already off) | screenshot | ✓ | 11 | 45 s | Hit the time dial with pixel coordinates alone. Verified in the clock app. |
| 6 | Find Android version + security update | tree | ✓ | 5 | 21 s | Through the settings search. Android 15, 5 Sep 2024. |
| 7 | Same | screenshot | ✓ (unfair) | 2 | 10 s | Settings reopened on the last page. |
| 8 | Alarm 6:45 "Agent Test v2" (DND on) | tree (v2) | ✓ | 16 | 70 s | v2: loop detection + "you may change ordinary settings when they block the task". The agent turned DND off directly and said so in its answer. |
| 9 | Android version (settings opened fresh) | screenshot (v2) | ✓ | 4 | 19 s | |
| 10 | WebView app: read the ☰ menu items | tree (v2) | ✗ | 40 | 166 s | The ☰ icon has no label and doesn't appear in the tree at all. The model only ever tried `tap(element=0)`. |
| 11 | Same | screenshot (v2) | ✓ | 4 | 24 s | Home, Zigarettenautomat Karte, Automaten aufstellen, Blog, WhatsApp Kanal (the app is German: home, cigarette machine map, set up machines, blog, WhatsApp channel) |
| 12 | Same | **auto** (v3) | ✓ | 5 | 27 s | tree → `look` → tapped ☰ at x,y from the screenshot. 2 screenshots sent to the model. |
| 13 | Same | hybrid (v3) | ✓ | 4 | 21 s | 4 screenshots sent to the model. |
| 14 | Chrome: search "Wetter Frankfurt" (German for "weather Frankfurt"), current temperature | **auto** (v3) | ✓ | 9 | 42 s | Closed Chrome's notification prompt, rejected Google cookies ("Alle ablehnen", "Reject all"), `look` → "12 °C, heavily cloudy" (checked against the screenshot). |
| 16 | Add a contact: name + German mobile number (redacted) | **auto** (v3) | ✓ | 9 | 56 s | Contacts → Create contact → `look` (the name field has no label) → typed name and number → Save. Checked in the contacts DB. The run folder is not published because it contains a real phone number. The first attempt was interrupted by an emulator crash; it ran stably after restarting with `-gpu swiftshader_indirect`. |
| 15 | Switch off every alarm named "Agent Test…", delete nothing | **auto** (v3) | ✓ | 8 | 35 s | Found and switched off all 3 (scrolled through the list), no screenshot needed. Checked in the tree. |

Typical model latency per step: **2–4 s** (Terra, effort low). One step including waiting for the UI to settle: about 4 s.
Tokens per step: tree mode about 1.5–3.5k input, screenshot mode about 2.5k input (one image at 720×1616, detail high).

## What these runs show

**The UI tree (accessibility)**
- Works well in native apps (Settings, Clock including the time dial, Calendar, Gmail, Chrome UI) and in the Compose/Godot apps. It is cheap, precise (taps by ID), and gives readable labels.
- WebViews (Capacitor/Cordova apps, Chrome pages) fill their tree only lazily. The first read was empty ("WebView" with no children). Fix: when a WebView is empty, wait 0.8 s and read again; that gave 20 elements.
- **Gaps:** icons without a label (☰ menus in web apps), images, maps, canvas and game content. Those don't show up in the tree at all, so pure tree mode fails (#10).

**Screenshot + x/y**
- GPT-6.1 Terra hits targets accurately by pixel coordinates (time dial, ☰ icon, buttons). OpenAI's native computer-use tool isn't needed for that, which matters because it isn't allowed on the ChatGPT plan route.
- Weaknesses: typing works only into the focused field, there is no structured data, and an image costs more tokens than the tree on simple screens.

**Recommendation: Auto mode (now the default)**
- The UI tree with element IDs is the default.
- A screenshot with numbered boxes is added only when it's needed:
  - the model calls `look`, or
  - the last two actions failed or didn't change the screen, or
  - the app window has fewer than 3 interactive elements (games, canvas, empty WebView).
- It solved #12 and #14 with only 2–3 images per task.

## Problems found and fixed

| Problem | Fix |
|---|---|
| The OAuth loopback server failed on Chrome's idle pre-connections ("Poll timed out") | One thread per connection; a connection without a request no longer aborts the sign-in |
| The overlay covered app buttons (search/today icons in Calendar) | Moved it into the status bar (top right) |
| The agent was too timid about blocking settings (DND) and went back and forth | Rule: ordinary settings may be changed and must be reported; loop detection marks repeated actions with `[REPEATED …]` |
| Empty WebView tree | Read again after 0.8 s |
| The model passes `element=0` / `x=0,y=0` alongside other arguments | A valid element wins; otherwise use x,y; a clear error message points to the valid IDs and `look` |
| The last step ran out without `finish` | The final step tells the model "LAST step: call finish now" |

## Not tested yet / open

- A real phone (only the emulator so far; the emulator crashed twice during the tests, unrelated to the agent as far as I can tell).
- Gmail and Calendar with a synced account (the emulator's test account needs a new sign-in).
- Typing in apps that block ACTION_SET_TEXT (the clipboard-paste fallback is built but unused so far).
- FLAG_SECURE screens (banking): the screenshot comes back black, so only the tree remains.
- Running in the background (virtual display), voice input, and confirmation dialogs before sending.
