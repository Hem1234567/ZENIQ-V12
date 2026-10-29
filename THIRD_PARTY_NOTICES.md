# Third-party components in the embedded terminal

| Component | Used for | License | Source |
|---|---|---|---|
| Termux `terminal-emulator` and `terminal-view` (v0.118.0), copied into `app/src/main/java/com/termux/` and `app/src/main/cpp/termux/` | Real PTY, ANSI terminal emulation, terminal widget | GPL-3.0 (parts derived from Terminal Emulator for Android are Apache-2.0) | https://github.com/termux/termux-app/tree/v0.118.0 |
| PRoot (green-green-avk fork, v0.15, prebuilt arm64 as `libproot.so` and `libproot-loader.so`) | Runs the Linux file system without root | GPL-2.0-or-later (talloc inside: LGPL-3.0) | https://github.com/green-green-avk/proot and https://github.com/green-green-avk/build-proot-android |
| Apache Commons Compress | Unpacks the Linux root file system | Apache-2.0 | https://commons.apache.org/proper/commons-compress/ |
| Eclipse JGit | Real git in the Code tab | EDL-1.0 (BSD-3) | https://www.eclipse.org/jgit/ |
| Alpine Linux minirootfs (downloaded on first use, not bundled) | Linux base system | Mixed open-source licenses | https://alpinelinux.org |
| Claude Code (downloaded on first use through npm, not bundled) | The Claude Code CLI | Anthropic terms: https://www.anthropic.com/legal/commercial-terms | https://www.npmjs.com/package/@anthropic-ai/claude-code |

**GPL note.** The Termux terminal code and PRoot are GPL software and are included in this APK. If you distribute the APK, you must also make the
complete corresponding source of the app available under a GPL-compatible license (publishing this repository publicly does that). If you ever want
to ship a closed-source build, replace these two components first.
