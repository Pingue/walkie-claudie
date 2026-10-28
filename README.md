# Walkie-Claudie

> Not an official Anthropic project.

---

<p align="center"><img src="docs/screenshots/intro-en.gif" width="820" alt="Intro: press talk on the phone and speak, the text goes to Claude Code on the PC, Claude fixes the test and replies, and the phone reads it aloud"></p>

### Overview
An Android app for talking to a Claude Code session on your PC like a walkie-talkie.

### Features
- Two-way voice radio: you ↔ a Claude Code session on your PC
- Use the floating button, the volume keys (push-to-talk style) or the Bluetooth earphone button as the radio key, whichever you like
- Monologue mode: for talking at length, it keeps listening through long pauses for up to 10 minutes and sends it all at once
- Multiple channels: each session is a channel
  - You can also hear replies on other channels (like a radio's channel scan)
- Open items: shows what Claude is keeping track of (questions waiting for your answer, approvals, tasks in progress)

### Background
My day job is parcel delivery. I tried vibe coding on my phone between drops, using Claude Code's Remote Control, but:

1. Typing out every little detail on a keyboard while delivering isn't easy.
2. Switching back and forth between the delivery app and the Claude app isn't easy either.
3. Reading Claude's replies closely while delivering isn't easy either.
4. When you deliver and vibe code at once, your eyes and hands are swamped, while your ears and mouth sit idle.

Putting those four together, the plan became: vibe code with my mouth and ears while delivering. After work, over a glass of soju, I casually pitched the plan to Claude Code. Surprisingly, it said "sure, that's doable", and here we are.

### How it works
<p align="center"><img src="docs/screenshots/diagram-en.png" width="820" alt="How it connects: the phone turns speech into text and sends it to the walkie plugin on the PC; the Claude Code session's reply comes back as text and the phone reads it aloud"></p>

- Talking: speech recognition on the phone → text sent to the walkie plugin on the PC → the plugin passes it to the Claude Code session
- Replies: Claude leaves its answer through the plugin's reply tool → the phone picks it up and reads it aloud
- No relay server: the phone connects straight to the PC

### Install and run

#### Specs
- Phone: Android 10+ (monologue mode: 13+)
- PC: tested on Linux only. macOS and Windows untested
- Connection: phone → PC ports 7700–7709, direct (HTTP, private addresses only)
- App language: English

#### Requirements
- Claude Code (signed in with a claude.ai plan), [Bun](https://bun.sh)
- A network where the phone can reach the PC: same Wi-Fi, or a VPN such as [Tailscale](https://tailscale.com) on the go

#### Setup
> You can also give Claude Code this repo's URL and ask it to install it. It can start the step 2 session for you too (e.g. in tmux; confirm the warning yourself). Steps 3–5 (phone) are up to you.

1. In a terminal, install the plugin on the PC
   ```sh
   claude plugin marketplace add ahn4jang27/walkie-claudie
   claude plugin install walkie@walkie-claudie
   ```
2. In a terminal, start a new Claude Code session with `WALKIE_ENABLE=1`
   ```sh
   WALKIE_ENABLE=1 claude --dangerously-load-development-channels plugin:walkie@walkie-claudie
   ```
   Windows PowerShell:
   ```powershell
   $env:WALKIE_ENABLE=1; claude --dangerously-load-development-channels plugin:walkie@walkie-claudie
   ```
   - Channels are a research preview, so this development flag is needed. A warning appears on every start → Enter
   - Without `WALKIE_ENABLE=1`, the plugin opens no port and no tools in that session
   - To keep an existing conversation: quit that session, then run the command above with `--continue` (latest conversation) or `--resume` (pick one)
3. Install the app APK on the phone ([Releases](../../releases) or "Build / develop" below)
   - Google Play Protect may block it, since it doesn't come from the Play Store. If it does:
     1. In the warning, tap "More details" → "Install anyway"
     2. Still blocked: Play Store › profile › Play Protect › Settings (gear) › turn off "Scan apps with Play Protect" → install the APK → turn it back on in the same place
4. Type `/walkie:pair` inside the session from step 2 → scan the QR in the app under **Settings › Key › QR**
   - QR looks broken: run `bun <plugin path>/pair.ts` in a terminal
   - Can't scan (e.g. it's on the same phone): copy the line from `pair.ts --text` → app **Settings › PC address › Paste**
5. Open the app → allow microphone and notifications → tap **On** on the main screen → follow the prompts to turn on "Display over other apps" (floating button) and Accessibility (volume keys). Details in [Permissions and accessibility](#permissions-and-accessibility) below

#### Permissions and accessibility
The app explains each step in a window, first when you open it and then when you tap On. Menu names vary a little by manufacturer and Android version (Samsung below, other phones in brackets). Can't find a menu? Search your phone's Settings for "accessibility" or "over other apps"

| Permission | Used for | Without it |
|---|---|---|
| Microphone (required) | Turns your speech into text with the phone's speech recognition. Only the text goes to the PC | You can't talk (replies still play) |
| Notifications (recommended) | Shows an "on" notification while the walkie is on | Still works, just no notification |
| Display over other apps | The floating button | No floating button |
| Accessibility | Hold volume down (talk) and volume up (monologue) | Volume keys don't work as talk keys |

1. Microphone and notifications: first-run window → Next → microphone "While using the app", notifications "Allow"
   - Said no? Phone Settings › Apps › Walkie-Claudie › Permissions › Microphone › "Allow only while using the app" (the app notices and offers an App info button too)
2. Display over other apps: tap On → Open settings in the window → (pick Walkie-Claudie if a list appears) → turn the switch on → come back and tap On again
   - Not using the floating button: "Turn on without it" in the window, or Settings › Floating button off
3. Accessibility (only if you want the volume keys): on your first On, tap Accessibility in the window → Installed apps (Pixel: Downloaded apps, others: Installed services etc.) › Walkie-Claudie → switch on → Allow
   - Why: while another app is on screen, Android hands volume key presses to other apps only through Accessibility. The app watches the volume keys only and doesn't read the screen. The "full control" warning is the same for every accessibility app
   - Switch greyed out, or a "Restricted setting" message (Android 13+, apps installed outside the Play Store): App info in the window (or phone Settings › Apps › Walkie-Claudie) › ⋮ top right › "Allow restricted settings" → turn it on in Accessibility again
   - To do it later: Settings › "Accessibility is off" row › How
   - Only using the floating or earphone button? You can skip it

Configuration (environment variables):

| Variable | Default | Meaning |
|---|---|---|
| `WALKIE_ENABLE` | (unset) | Only sessions with `1` turn the walkie on |
| `WALKIE_PORT` | `7700` | First channel's port (next sessions +1, up to 10) |
| `WALKIE_NAME` | session folder | Channel name |
| `WALKIE_STATE_DIR` | `~/.claude/channels/walkie` | Where the key and pending replies live |

##### Build / develop
- App: `cd android && ./gradlew assembleDebug` (JDK 17+, Android SDK) → `android/app/build/outputs/apk/debug/app-debug.apk`
- Regenerate the beeps: `python3 tools/sounds.py android/app/src/main/res/raw`
- Plugin tests: `cd plugin && bun install && bun test`
- App unit tests: `cd android && ./gradlew testDebugUnitTest`
- CI: pull requests run both test suites and check that the PR title is a conventional commit (`.github/workflows/ci.yml`).
- Releases are automatic. Every push to `main` builds an APK (`.github/workflows/build-apk.yml`, downloadable from the run's artifacts). If the [conventional commits](https://www.conventionalcommits.org) since the last `v*` tag call for it, the workflow also picks the next [semver](https://semver.org) version, tags the commit and publishes a GitHub release with the APK attached:
  - `fix:` / `perf:` / `revert:` → patch, `feat:` → minor, `type!:` or a `BREAKING CHANGE:` footer → major
  - `docs:`, `chore:`, `ci:`, `test:` and the like don't cause a release
  - the version is stamped into the app (`versionName`, and `versionCode` = major×10000 + minor×100 + patch); the first release continues from 0.1.1
  - to get a signed release APK, add the secrets `WALKIE_KEYSTORE_BASE64`, `WALKIE_STORE_PASSWORD`, `WALKIE_KEY_ALIAS` and `WALKIE_KEY_PASSWORD`; without them the release carries the debug-signed APK
  - the release logic is `tools/next-version.ts` (tests: `bun test tools/`)

##### Microphone
Settings › Microphone cycles Auto (Android decides), Phone (built-in mic) and Headset (wired, USB or Bluetooth). If the chosen kind isn't connected, the default is used. Monologue mode honours the choice exactly; the normal talk buttons go through the phone's speech recognizer, which has no device option, so there it is a best-effort routing hint on Android 12+.

### Screens
<p align="center">
  <img src="docs/screenshots/en-main.png" width="200" alt="Main screen: usage, open items, conversation">
  <img src="docs/screenshots/en-ch.png" width="200" alt="Channel list">
  <img src="docs/screenshots/en-set.png" width="200" alt="Settings">
  <img src="docs/screenshots/home.png" width="200" alt="Floating mini walkie button on the home screen">
</p>

- Main screen: top channel, connection, usage / middle open items / bottom conversation / keys Settings, On/Off, Talk
  - On/Off key light: green on / dark off. While off, the conversation dims and "Off" appears
- Channel list: tap the top LCD (`CH1 name ›`)
- Settings: PC address and key, talk buttons on/off, microphone (Auto / Phone / Headset), channels, voice, button test
- Floating button: a small walkie over other apps. Hidden while the app is on screen
  - Light: green connected / red transmitting / blinking red disconnected

### Usage

#### Basic flow
1. Turn on: tap On on the main screen → one buzz + rising power tone
2. Talk: with whichever of the three buttons you like (each switchable in Settings)
   - Floating button: tap the mic → chirp → talk. Short pauses don't cut you off (2 minutes max)
   - Hold volume down: talk while holding. Short presses still change the volume. Screen on only; needs Accessibility ([Permissions and accessibility](#permissions-and-accessibility))
   - Bluetooth earphone button: press once (or twice) to start. Works with the screen off
3. Send: tap the floating or earphone button again, or release volume down → a beep once the PC has it
4. Hear the reply: call-alert beep → the reply is read aloud
5. Turn off: tap Off → two buzzes + falling power tone

#### Extras
- Monologue mode (talking at length): hold volume up → three rising tones → let go and talk → hold volume up again to send. Long pauses are fine (10 minutes max). Starts only with the screen on (it then keeps the screen on)
- Channels (several sessions): each `WALKIE_ENABLE=1` session takes a free port (7700, 7701, …) as CH1, CH2, …
  - Tap the top LCD for the channel list → pick one to switch; its name is read out
  - The name is the session folder; change it with `WALKIE_NAME=assistant`
- Scan (on by default): listens to every open channel. Replies from other channels play after a double-beep and go into that channel's history; you still talk on the current channel
  - Turn it off under Settings › Channels; turn on "Follow the replying channel" to switch to it
- Change the voice: pick the engine, voice and speed in the Voice screen
- During calls (VoIP included): earphone button = answer/hang up, volume keys = call volume. Replies that arrive meanwhile are read after the call
- When disconnected: what you said keeps being resent until the connection is back

### More

#### Security
- What you say through the app can make the Claude on your PC run commands → keep the key safe
- Key: generated on first run (`~/.claude/channels/walkie/key`); requests with a wrong key are refused
- Only connections from private addresses (192.168.x, 10.x, 172.16–31.x, Tailscale 100.64–127.x) are accepted
- Traffic is not encrypted → on shared networks such as café Wi-Fi it can be read. Tailscale encrypts it
- If the key leaks: delete the key file → restart the session → pair again

#### Troubleshooting
- App stays "Disconnected": check the phone can reach the PC (same Wi-Fi or Tailscale), the PC firewall allows ports 7700–7709, and the session was started with `WALKIE_ENABLE=1`
- "HTTP 401": wrong key → pair again with `/walkie:pair`
- Earphone button does nothing: a music or podcast app has it → long-press the floating button
- App install blocked: Play Protect → see Setup step 3
- Accessibility switch greyed out: App info › ⋮ › Allow restricted settings first ([Permissions and accessibility](#permissions-and-accessibility))
- Holding a volume key only changes the volume: Accessibility is off → Settings › "Accessibility is off" › How
- Usage doesn't show: the usage check is unofficial and may break after a Claude Code update → add `"statusLine": {"type": "command", "command": "bun <plugin path>/statusline.ts"}` to `settings.json`
- Which key does a button send: Settings › Button test

## License
MIT
