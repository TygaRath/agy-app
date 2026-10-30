# Agy app (v0.2)

Android front-end for `agy` running in Termux.

## 1. Start the bridge (in Termux)

    cd ~/agy-app/bridge && python agy-bridge.py

Test: `curl http://127.0.0.1:8765/health`

## 2. Build the app

Push to GitHub; the "Build APK" workflow produces `app-debug.apk` under Actions > Artifacts.

## What works now

- Connection check on launch, with a setup screen if the bridge is down
- Live chat: each message runs `agy -p ... --output-format stream-json`; replies stream in
- Conversation continues across messages (`--conversation <id>`); "+" starts a new chat
- Tool calls shown as expandable cards; markdown and code blocks (with copy button)
- Stop button cancels the running turn

## Next steps

1. Permission approvals as tap-to-approve cards
2. Voice input, image attach/display
3. Terminal view toggle, multiple sessions
