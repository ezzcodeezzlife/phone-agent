# Phone Agent (prototype)

An Android app where an AI agent operates the phone: it reads the screen, then taps, swipes and types in any app to finish a task.
AI requests run on the user's **ChatGPT plan** through OpenAI's *Sign in with ChatGPT*, so no API key is needed.

Status: prototype, tested on the emulator (Pixel 9, Android 15) and a Xiaomi phone.

**Download:** [phone-agent.apk (latest build)](https://github.com/ezzcodeezzlife/phone-agent/releases/latest/download/phone-agent.apk). Android 11 or newer. Every push to `main` builds a new signed APK through GitHub Actions ([all releases](https://github.com/ezzcodeezzlife/phone-agent/releases)).

## Setup for users

1. Download the APK above and install it (allow installs from your browser or file manager). If Play Protect blocks it ("App blocked to protect your device"), switch off *Scan apps with Play Protect* in the Play Store's Play Protect settings for the install, or install it with `adb install`.
2. Under **Accessibility**, switch on **Phone Agent** (the only system setting needed). When the APK is installed outside a store on Android 13+, first allow it under App info → ⋮ → "Allow restricted settings".
3. **Continue with ChatGPT**: sign in in the browser and allow ChatGPT plan usage (Plus/Pro).
4. Optional: allow the microphone to give tasks and corrections by voice.
5. Say or type a task on Home and press **Start**.

## Using it

**While the agent works**, a small island sits at the bottom of the screen: what the agent is doing right now, plus mic, pause and stop.

- **Pull the island up** (or tap it) for the full panel. It shows the step, the app and the model, everything the agent did so far, and a text field with a mic.
- **Steer at any time.** Type or say a correction ("make it 7:00 instead"). It goes into the very next step; actions the agent had planned for the old screen are dropped.
- **Take over.** Touches outside the island go to the phone as usual. The agent waits while you tap and then continues from the screen you left it on.
- **Questions.** When a task is ambiguous (two contacts called Alex, no time given), the agent asks in the panel. Answer with a tap, by typing or by voice.
- **Confirm before anything irreversible.** Sending, posting, paying and deleting first show exactly what will happen and wait for your OK. You can switch this off in Settings.
- **Follow-ups.** After a run, type or say the next task in the same panel. It continues from the current screen and knows the previous result.

The overlay is hidden from the screenshots sent to the model, and the agent's own taps pass through it to the app below.

**In the app:**

- **Home:** task input with voice, model / effort / "ask first" chips, setup steps and recent runs.
- **Runs:** every finished run, grouped by day and filterable. A run shows its answer, stats, each step (including your messages and answers) and the last screen.
- **Usage:** tasks, tokens and agent time for 7 days, 30 days or all time, tokens per day, averages per task and a breakdown by model. Plan limits themselves are only shown by ChatGPT, so the screen links there.
- **Settings:** account, permissions, model, reasoning effort, screen-reading mode, "ask before sending, paying or deleting", "wait while I touch the screen", voice language, run history and a debug log.

## Architecture

```
MainActivity  ── Home / Runs / Usage / Settings (plain views, built in code)
AgentService  ── AccessibilityService (the one switch)
   observe(): accessibility tree of all windows → numbered element list
              + screenshot (takeScreenshot, scaled to 720 px wide, boxes in hybrid mode)
   act():     dispatchGesture (tap / long press / swipe), ACTION_SET_TEXT, IME enter,
              scroll actions, global actions (back/home/…), launch intents
Overlay       ── island + chat panel (TYPE_ACCESSIBILITY_OVERLAY); watches outside touches,
              lets the agent's gestures through, hides itself for model screenshots
Voice         ── SpeechRecognizer with live partial results
Agent         ── loop: observe → Responses API (function tools) → execute → repeat (max 40 steps);
              user messages, pause, hold-while-touching, ask_user and confirm
Runs          ── run history on disk (summary.json with steps, tokens, timing) → Runs + Usage screens
Auth          ── Sign in with ChatGPT (dynamic client registration, PKCE, loopback 127.0.0.1:1455)
OpenAI        ── POST /v1/responses with store=false, stream=true (SSE)
```

No third-party dependencies (HttpURLConnection, org.json, java.security).

### Observation modes (switch in the app)

| Mode | The model gets | Actions use |
|---|---|---|
| `auto` (default) | The tree. A screenshot with numbered boxes is added only when the model calls `look`, when the last 2 actions failed or didn't change the screen, or when the app window has fewer than 3 interactive elements | IDs, x,y from the screenshot |
| `tree` | Text list `[id] Type "label" flags @(x,y)` from the accessibility tree | Element IDs (taps at the element center) |
| `screenshot` | Only the screenshot (720×1616) | Pixel coordinates x,y |
| `hybrid` | Screenshot with numbered boxes plus the element list | IDs, coordinates as a fallback |

### Sign in with ChatGPT (open-source flow)

- First sign-in: `client_id=dynamic_agent_client`, `agent_name_hint=Phone Agent`, `ext_agent_host_id=urn:uuid:…`. OpenAI returns an issued `client_id`, which the app stores and reuses.
- Scopes: `openid profile email offline_access resource.invoke chatgpt.tokens.use.direct`, `resource=https://api.openai.com/v1`.
- Redirect: `http://127.0.0.1:<port>/auth/callback`. The app runs a small HTTP listener; Chrome on the same phone reaches it.
- Checks: ID token (RS256 against JWKS, iss/aud/exp/nonce) and that the `chatgpt.tokens.use.direct` scope was granted. Access tokens last 1 h; refresh tokens rotate.
- Restrictions on this route:
  - `store:false`, `stream:true`.
  - Function tools must sit in a `namespace`.
  - OpenAI's **native computer-use tool is not allowed**, so screenshot mode works through our own `tap(x,y)` functions.
- Plus has a 5-hour limit shared across all apps; usage is listed at chatgpt.com/settings/usage.
- Officially meant for open-source or locally run apps. A paid or hosted app needs OpenAI's interest form.

## Development and testing

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# switch on accessibility without the UI (emulator):
adb shell settings put secure enabled_accessibility_services dev.phoneagent/dev.phoneagent.AgentService
adb shell settings put secure accessibility_enabled 1
```

Sign in through the desktop browser (useful in the emulator):

```bash
adb shell am start -n dev.phoneagent/.MainActivity --ez login true --ez no_browser true
adb logcat -d -s PhoneAgent:I | grep AUTH_URL      # open this URL on the PC
adb forward tcp:1455 tcp:1455                       # the callback then reaches the app
```

Start a task from adb (debug builds only):

```bash
adb shell am start -n dev.phoneagent/.MainActivity --es task "…" --es mode tree --es effort low --ez run true
```

Save what the model sees on the current screen, without an AI call:

```bash
adb shell am broadcast -a dev.phoneagent.DUMP -p dev.phoneagent --es name gmail-inbox
adb pull /sdcard/Android/data/dev.phoneagent/files/dumps/
```

Every run writes `files/runs/<time>-<mode>/` with `step-NN.jpg`, `step-NN-tree.txt`, `log.txt` and `summary.json` (screenshots and trees are kept for the newest 20 runs). Selected runs are in [`docs/test-runs/`](docs/test-runs/).

## Test results

See [`docs/RESULTS.md`](docs/RESULTS.md).
