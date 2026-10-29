# Embedded terminal with the real Claude Code CLI

Code tab > **Claude Code**. Everything runs inside ZENIQ; no Termux app, no root.

## How it works
1. `libproot.so` + `libproot-loader.so` ship in the APK (`jniLibs/arm64-v8a`, extracted to the app's native library folder, the only place Android lets an app execute files from).
2. First setup downloads the official Alpine Linux minirootfs (arm64, SHA-256 checked against Alpine's own release list), unpacks it to `files/term/rootfs`, then runs
   `apk add bash git curl nodejs npm ripgrep libgcc libstdc++ ca-certificates nano` and `npm install -g @anthropic-ai/claude-code`
   (the officially documented route; Alpine 3.19+ and linux-arm64-musl are supported platforms, `USE_BUILTIN_RIPGREP=0` is set).
3. `claude --version` must run inside the environment before the screen says **Ready**.
4. The terminal is a real PTY (Termux terminal library): colors, Ctrl+C/D, Tab, Esc, arrows, resize, scrollback, UTF-8.
5. The open ZENIQ project is mounted at `/workspace` and is the working directory. Opening another project restarts the shell there.

## Persistence
Environment and settings live in `files/term` (survive app restarts and phone reboots). `state.properties` records finished steps, so a restart never
re-downloads valid parts; interrupted downloads resume (HTTP Range), interrupted unpacking is redone from a temp folder. Lost on: clearing app data, uninstall.
A running shell ends if Android kills the app process; the installed environment stays.

## Network
Checks separate offline / DNS failure / TLS failure / server unreachable. Downloads: 15 s connect and 30 s read timeouts, max 4 attempts with 3, 6, 12 s back-off,
no infinite loops, normal certificate checks (never disabled). DNS servers are copied from the active Android network, nothing is hard-coded.
If Alpine's https package index fails, `apk` falls back to its http index; packages are still verified by apk's signatures.
Claude Code itself needs internet to reach Anthropic. Anthropic's hosted model does not run offline. The shell, files and local tools work offline.

## Sign-in
Done by Claude Code itself on first start (browser link or API key). **Sign out** deletes `~/.claude/.credentials.json` and `~/.claude.json`.
ZENIQ never reads, stores or logs credentials; the setup log contains installer output only.

## Buttons
Start setup / Resume / Retry, Cancel, Repair environment (reinstalls tools and Claude Code, keeps Linux), Reset all (asks first; projects are never touched),
View logs, Check connection, Run Claude, Restart, Clear, Sign out.

## Test report (honest)
Executed here: Kotlin parser pass over all new files (no syntax errors); source and license review of the Termux libraries and PRoot; verification of the official Claude Code
install requirements from Anthropic's docs. **Not executed (no Android device, emulator, Android SDK or NDK in the build sandbox):** compilation of the app, the native `termux` library,
APK build, and every runtime test in your list (clean install, CLI install, `claude` start, interrupted download, offline start, app kill, reboot, storage exhaustion,
corrupted install, keyboard, Ctrl+C, reopen without re-download). Steps to run them: install the APK, open Code > a project > Claude Code > Start setup; then toggle airplane mode during
the download, force-stop the app, reboot, and reopen; run `sleep 1000` then ^C; run `claude` in a project.

## Known limitations
- arm64 phones only. About 1.2 GB free storage needed (Alpine + Node + Claude Code).
- PRoot is slower than native Linux (system calls are intercepted). Some phones/ROMs block ptrace; the self-test reports that clearly.
- Alpine uses musl; Claude Code's musl build is documented as supported, but this exact combination (PRoot on Android) is untested by me.
- Network tools that need raw sockets (ping) or a real /proc may not work.
- Screenshot inspection (`look_at_screen`) needs Android 11+, the accessibility service re-enabled once, and an OpenRouter key; the screenshot is sent to the online model for that call only.
- A local model for Claude Code is not wired in (hosted Claude is never presented as offline); the environment keeps that boundary open for a compatible local server later.
