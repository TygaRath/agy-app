# Agy app (v0.3)

Android front-end for `agy` running in Termux.

## 1. Start the bridge (in Termux)

    cd ~/agy-app/bridge && python agy-bridge.py

First run prints a pairing token (also in ~/.agy-bridge-token). Paste it into the app once.

## 2. Build the app

Push to GitHub; the "Build APK" workflow produces `app-debug.apk` under Actions > Artifacts.

## Features

- Live chat over `agy -p --output-format stream-json`; conversations continue (`--conversation`)
- Tool-call cards, markdown, code blocks with copy
- Approval cards: agy soft-denies anything needing approval in print mode, so the app shows
  "Allow once / Always / Deny" and retries the turn with an allow rule (`--settings`)
- Modes: Ask / Accept edits / Auto / Plan
- Voice input (system speech dialog), image attach (uploaded to ~/agy-uploads), images shown inline
- Type `/` for app commands (/new /settings /tools) and your agy skills
- Tools screen: plugins, agents, MCP servers, models (read-only)
- Settings: working folder, model, agent, reasoning effort, pairing token

## Known limits (print mode)

- agy's own `ask_question` is auto-skipped in print mode, so questions arrive as normal chat text
- Built-in TUI slash commands (/config, /agents panel, ...) don't run in print mode
