# Phone Agent (prototype)

An Android app where an AI agent operates the phone: it reads the screen, then taps, swipes and types in any app to finish a task.
AI requests run on the user's **ChatGPT plan** through OpenAI's *Sign in with ChatGPT*, so no API key is needed.

Status: prototype, tested only on the emulator (Pixel 9, Android 15).

**Download:** [phone-agent.apk (latest build)](https://github.com/ezzcodeezzlife/phone-agent/releases/latest/download/phone-agent.apk). Android 11 or newer. Every push to `main` builds a new signed APK through GitHub Actions ([all releases](https://github.com/ezzcodeezzlife/phone-agent/releases)).

## Setup for users

1. Download the APK above and install it (allow installs from your browser or file manager).
2. Under **Accessibility**, switch on **Phone Agent** (the only system setting needed). When the APK is installed outside a store on Android 13+, first allow it under App info → ⋮ → "Allow restricted settings".
3. **Continue with ChatGPT**: sign in in the browser and allow ChatGPT plan usage (Plus/Pro).
4. Pick a model, a mode and a task, then press ▶ Start. A small "Phone Agent ■ Stop" pill at the top right shows progress and stops the run.

## Architecture

```
MainActivity  ── sign-in, model/mode picker, task, log
AgentService  ── AccessibilityService (the one switch)
   observe(): accessibility tree of all windows → numbered element list
              + screenshot (takeScreenshot, scaled to 720 px wide, boxes in hybrid mode)
   act():     dispatchGesture (tap / long press / swipe), ACTION_SET_TEXT, IME enter,
              scroll actions, global actions (back/home/…), launch intents
Agent         ── loop: observe → Responses API (function tools) → execute → repeat (max 40 steps)
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

Every run writes `files/runs/<time>-<mode>/` with `step-NN.jpg`, `step-NN-tree.txt`, `log.txt` and `summary.json`. Selected runs are in [`docs/test-runs/`](docs/test-runs/).

## Test results

See [`docs/RESULTS.md`](docs/RESULTS.md).
