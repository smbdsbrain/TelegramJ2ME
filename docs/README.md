# TelegramJ2ME documentation

| Page | What is in it |
|---|---|
| [building.md](building.md) | Prerequisites on Windows, Linux and macOS; bootstrap, build, test, credentials, live testing, and the rules for writing a cross-platform build script |
| [architecture.md](architecture.md) | The `Transport` / `MtLink` seam, package map, build targets, how the crypto is verified, memory and threading discipline, durable state, and an honest statement of the security posture |
| [toolchain.md](toolchain.md) | Version matrix and pins, why JDK 8 specifically, how preverification works, the two bootclasspath modes, build outputs |
| [emulator-notes.md](emulator-notes.md) | What an emulator pass does and does not prove, the automated smoke test, the menu-ordering rule it enforces, MicroEmulator and WTK gotchas |
| [releasing.md](releasing.md) | Repository secrets, cutting a tagged release, what gets published, dry runs |
| [screenshots/](screenshots/) | How the README screenshots are rendered, and why every name in them is fictional |

Start with [building.md](building.md) if you want to compile it, and
[architecture.md](architecture.md) if you want to understand it.
