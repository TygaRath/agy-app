#!/usr/bin/env python3
"""
agy-bridge v0.3 - runs inside Termux. The Android app talks to it on localhost.

  GET  /health              open; {"ok": true, "agy_installed": ...}
  GET  /auth                token; 200 if the token is valid
  GET  /file?path=...       token; serves an image file under your Termux home
  GET  /commands?cwd=...    token; lists skills usable as /commands
  GET  /cli?c=plugins       token; read-only agy info: plugins | agents | mcp | models
  POST /upload?name=x.png   token; raw image bytes -> saved in ~/agy-uploads
  POST /chat                token; streams agy's stream-json output (NDJSON)
        body: {"prompt", "conversation_id", "mode", "cwd", "model", "effort",
               "agent", "allow": ["command(git status)", ...], "images": [paths]}

The token lives in ~/.agy-bridge-token (created on first run). It stops other apps
on the phone from using the bridge. Send it as header X-Agy-Token or query ?t=.

Run:  python agy-bridge.py
"""
import hmac
import json
import mimetypes
import os
import re
import secrets
import shutil
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HOST = "127.0.0.1"
PORT = 8765
VERSION = "0.3.0"

HOME = os.path.expanduser("~")
HOME_REAL = os.path.realpath(HOME)
UPLOAD_DIR = os.path.join(HOME, "agy-uploads")
TOKEN_FILE = os.path.join(HOME, ".agy-bridge-token")
AGY_DIR = os.path.join(HOME, ".gemini", "antigravity-cli")
IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp"}
MAX_UPLOAD = 25 * 1024 * 1024
MODES = ("accept-edits", "auto", "plan")
EFFORTS = ("low", "medium", "high", "xhigh", "max")
CLI_COMMANDS = {
    "plugins": ["plugin", "list"],
    "agents": ["agents"],
    "mcp": ["mcp", "list"],
    "models": ["models"],
}
RULE_RE = re.compile(r"(command|write_file|read_file|mcp)\([^\n]{1,500}\)")
NAME_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,59}")
STDERR_HINTS = ("permission", "denied", "allow", "headless")


def load_token():
    try:
        with open(TOKEN_FILE) as f:
            t = f.read().strip()
        if t:
            return t
    except FileNotFoundError:
        pass
    t = secrets.token_urlsafe(18)
    fd = os.open(TOKEN_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(t + "\n")
    return t


TOKEN = load_token()


def agy_info():
    path = shutil.which("agy")
    if not path:
        return {"agy_installed": False, "agy_path": None, "agy_version": None}
    version = None
    try:
        out = subprocess.run([path, "--version"], capture_output=True, text=True, timeout=5)
        text = (out.stdout or out.stderr or "").strip()
        version = text.splitlines()[-1] if text else None
    except Exception:
        pass
    return {"agy_installed": True, "agy_path": path, "agy_version": version}


def error_line(message):
    return json.dumps({"event": "result", "result": {"status": "ERROR", "response": message}}) + "\n"


def read_frontmatter(path, default_name):
    name, desc = default_name, ""
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            head = f.read(3000)
        if head.startswith("---"):
            block = head.split("---", 2)[1] if head.count("---") >= 2 else ""
            m = re.search(r"^name:\s*(.+)$", block, re.M)
            if m:
                name = m.group(1).strip().strip("'\"")
            m = re.search(r"^description:\s*(.+)$", block, re.M)
            if m:
                desc = m.group(1).strip().strip("'\"")
    except OSError:
        pass
    return name, desc[:160]


def scan_skills(root, source, found):
    if not os.path.isdir(root):
        return
    for entry in sorted(os.listdir(root)):
        p = os.path.join(root, entry)
        md = None
        stem = entry
        if os.path.isdir(p) and os.path.isfile(os.path.join(p, "SKILL.md")):
            md = os.path.join(p, "SKILL.md")
        elif entry.endswith(".md") and os.path.isfile(p):
            md, stem = p, entry[:-3]
        if md:
            name, desc = read_frontmatter(md, stem)
            if name not in found:
                found[name] = {"name": name, "description": desc, "source": source}


def list_commands(cwd):
    found = {}
    if cwd:
        scan_skills(os.path.join(cwd, ".agents", "skills"), "workspace", found)
    scan_skills(os.path.join(AGY_DIR, "skills"), "global", found)
    plugins = os.path.join(AGY_DIR, "plugins")
    if os.path.isdir(plugins):
        for plug in sorted(os.listdir(plugins)):
            scan_skills(os.path.join(plugins, plug, "skills"), f"plugin:{plug}", found)
    return list(found.values())


class Handler(BaseHTTPRequestHandler):
    # ---- helpers -------------------------------------------------------------
    def _json(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self, query):
        supplied = self.headers.get("X-Agy-Token") or (query.get("t") or [""])[0]
        return hmac.compare_digest(supplied.encode(), TOKEN.encode())

    # ---- GET -----------------------------------------------------------------
    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        if u.path == "/health":
            return self._json(200, {"ok": True, "bridge_version": VERSION, **agy_info()})
        if not self._authorized(q):
            return self._json(401, {"ok": False, "error": "bad token"})
        if u.path == "/auth":
            return self._json(200, {"ok": True})
        if u.path == "/file":
            return self._file((q.get("path") or [""])[0])
        if u.path == "/commands":
            cwd = os.path.expanduser((q.get("cwd") or [""])[0]) or HOME
            return self._json(200, {"ok": True, "commands": list_commands(cwd)})
        if u.path == "/cli":
            return self._cli((q.get("c") or [""])[0])
        self._json(404, {"ok": False, "error": "not found"})

    def _file(self, path):
        real = os.path.realpath(os.path.expanduser(path))
        ext = os.path.splitext(real)[1].lower()
        inside_home = real == HOME_REAL or real.startswith(HOME_REAL + os.sep)
        if not inside_home or ext not in IMAGE_EXTS or not os.path.isfile(real):
            return self._json(404, {"ok": False, "error": "not found"})
        ctype = mimetypes.guess_type(real)[0] or "application/octet-stream"
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(os.path.getsize(real)))
        self.send_header("Cache-Control", "max-age=3600")
        self.end_headers()
        with open(real, "rb") as f:
            shutil.copyfileobj(f, self.wfile)

    def _cli(self, name):
        args = CLI_COMMANDS.get(name)
        agy = shutil.which("agy")
        if not args or not agy:
            return self._json(400, {"ok": False, "error": "unknown command or agy missing"})
        try:
            out = subprocess.run(
                [agy] + args, capture_output=True, encoding="utf-8", errors="replace",
                timeout=25, stdin=subprocess.DEVNULL, cwd=HOME,
            )
            text = out.stdout.strip()
            if not text:
                text = "\n".join(
                    ln for ln in out.stderr.splitlines() if "logging before google.Init" not in ln
                ).strip()
            self._json(200, {"ok": True, "output": text or "(no output)"})
        except subprocess.TimeoutExpired:
            self._json(200, {"ok": True, "output": "(timed out)"})

    # ---- POST ----------------------------------------------------------------
    def do_POST(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        if not self._authorized(q):
            return self._json(401, {"ok": False, "error": "bad token"})
        if u.path == "/upload":
            return self._upload(q)
        if u.path == "/chat":
            return self._chat()
        self._json(404, {"ok": False, "error": "not found"})

    def _upload(self, q):
        length = int(self.headers.get("Content-Length", 0))
        if length <= 0 or length > MAX_UPLOAD:
            return self._json(413, {"ok": False, "error": "bad upload size"})
        data = self.rfile.read(length)
        name = re.sub(r"[^A-Za-z0-9._-]", "_", (q.get("name") or ["image.jpg"])[0])[-60:]
        if os.path.splitext(name)[1].lower() not in IMAGE_EXTS:
            return self._json(400, {"ok": False, "error": "only image files"})
        os.makedirs(UPLOAD_DIR, exist_ok=True)
        path = os.path.join(UPLOAD_DIR, f"{int(time.time() * 1000)}_{name}")
        with open(path, "wb") as f:
            f.write(data)
        self._json(200, {"ok": True, "path": path})

    def _chat(self):
        try:
            length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(length) or b"{}")
        except Exception:
            return self._json(400, {"ok": False, "error": "bad json"})

        prompt = (body.get("prompt") or "").strip()
        if not prompt:
            return self._json(400, {"ok": False, "error": "empty prompt"})

        # Only files we saved ourselves may be attached.
        upload_real = os.path.realpath(UPLOAD_DIR) + os.sep
        images = [
            p for p in (body.get("images") or [])
            if isinstance(p, str) and os.path.realpath(p).startswith(upload_real)
        ]
        if images:
            prompt += "\n\nAttached image file(s) - open them with your file viewing tool:\n"
            prompt += "\n".join(f"- {p}" for p in images)

        # Streaming response: no Content-Length, connection closes when done (HTTP/1.0).
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()

        agy = shutil.which("agy")
        if not agy:
            self.wfile.write(error_line("agy was not found in Termux's PATH.").encode())
            return

        cmd = [agy, "-p", prompt, "--output-format", "stream-json"]
        conv = body.get("conversation_id")
        if conv:
            cmd += ["--conversation", str(conv)]
        if body.get("mode") in MODES:
            cmd += ["--mode", body["mode"]]
        if body.get("effort") in EFFORTS:
            cmd += ["--effort", body["effort"]]
        model = (body.get("model") or "").strip()
        if model and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/-]{0,79}", model):
            cmd += ["--model", model]
        agent = (body.get("agent") or "").strip()
        if agent and NAME_RE.fullmatch(agent):
            cmd += ["--agent", agent]
        allow = [a for a in (body.get("allow") or []) if isinstance(a, str) and RULE_RE.fullmatch(a)]
        if allow:
            cmd += ["--settings", json.dumps({"permissions": {"allow": allow}})]

        cwd = os.path.expanduser(body.get("cwd") or "") or HOME
        if not os.path.isdir(cwd):
            cwd = HOME

        proc = subprocess.Popen(
            cmd, cwd=cwd, encoding="utf-8", errors="replace", bufsize=1,
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )

        # agy explains permission denials on stderr; keep only those lines.
        hints = []

        def drain():
            try:
                for ln in proc.stderr:
                    if any(h in ln.lower() for h in STDERR_HINTS):
                        hints.append(ln.strip()[:400])
            except Exception:
                pass

        drainer = threading.Thread(target=drain, daemon=True)
        drainer.start()

        saw_result = False
        try:
            for line in proc.stdout:
                if '"event":"result"' in line.replace(" ", ""):
                    saw_result = True
                self.wfile.write(line.encode())
                self.wfile.flush()
            code = proc.wait()
            drainer.join(timeout=1)
            if hints:
                self.wfile.write((json.dumps({"event": "bridge_stderr", "lines": hints[:10]}) + "\n").encode())
                self.wfile.flush()
            if not saw_result:
                self.wfile.write(error_line(f"agy exited with code {code} without a result.").encode())
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass  # app disconnected (Stop pressed or app closed)
        except Exception as e:
            import traceback
            traceback.print_exc()
            try:
                self.wfile.write(error_line(f"bridge error: {e}").encode())
                self.wfile.flush()
            except Exception:
                pass
        finally:
            if proc.poll() is None:
                proc.kill()
                proc.wait()

    def log_message(self, fmt, *args):
        # Log only the path, never the query string (it can carry the token).
        print(f"[bridge] {self.command} {urlparse(self.path).path}")


if __name__ == "__main__":
    print(f"agy-bridge {VERSION} listening on http://{HOST}:{PORT}")
    print(f"Pairing token (paste into the app once): {TOKEN}")
    print(f"Or show it later with: cat {TOKEN_FILE}")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
