# TelegramJ2ME documentation

| Page | What is in it |
|---|---|
| [building.md](building.md) | Prerequisites on Windows, Linux and macOS; bootstrap, build, test, credentials, live testing, and the rules for writing a cross-platform build script |
| [installing.md](installing.md) | 1.0 RC normal/minified files, in-place upgrade from 0.8.1, and handset preparation |
| [architecture.md](architecture.md) | The `Transport` / `MtLink` seam, package map, build targets, how the crypto is verified, memory and threading discipline, durable state, and an honest statement of the security posture |
| [1.0-stability-contract.md](1.0-stability-contract.md) | The bounded 1.0 promise, cache migration and downgrade boundary, and release-candidate handoff order |
| [toolchain.md](toolchain.md) | Version matrix and pins, why JDK 8 specifically, how preverification works, the two bootclasspath modes, build outputs |
| [emulator-notes.md](emulator-notes.md) | What an emulator pass does and does not prove, the automated smoke test, the menu-ordering rule it enforces, MicroEmulator and WTK gotchas |
| [diagnostics.md](diagnostics.md) | Getting measurements and crash tails off a handset that has no console: the report collector, what is redacted before anything is sent, and why published builds cannot upload |
| [hardware/](hardware/) | What has actually been measured on each physical handset, and what each one broke |
| [releasing.md](releasing.md) | Repository secrets, cutting a tagged release, what gets published, dry runs |
| [testing/1.0-failure-matrix.md](testing/1.0-failure-matrix.md) | Scenario IDs, tier-specific evidence, and explicit `NOT RUN` rows |
| [screenshots/](screenshots/) | How the README screenshots are rendered, and why every name in them is fictional |

Start with [building.md](building.md) if you want to compile it, and
[architecture.md](architecture.md) if you want to understand it.
