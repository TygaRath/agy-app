# Agy app (skeleton v0.1)

Android front-end for `agy` running in Termux.

## Step 1: start the bridge (in Termux)

    pkg install python
    python agy-bridge.py

Copy `bridge/agy-bridge.py` to Termux first (e.g. via shared storage, or paste it with `nano`).
Test it: `curl http://127.0.0.1:8765/health`

## Step 2: build the app

Open this folder in Android Studio, let Gradle sync, then Run (or Build > Build APK).

## What works now

- On launch the app checks `http://127.0.0.1:8765/health`
- Connected: chat-style screen (status dot shows whether `agy` was found)
- Not connected: setup screen with the command to run and a Retry button

## Next steps

1. Bridge: PTY + WebSocket running `agy`
2. App: send/receive messages over the socket
3. Markdown + code block rendering, terminal view toggle
4. Voice input, image attach/display
