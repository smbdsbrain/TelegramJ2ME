# Showcase screenshots

These images are rendered from the real `DialogListScreen` and `ChatScreen`
Canvas implementations at the target handset's native 320×240 resolution, then
scaled 2× without smoothing for clearer display on GitHub.

All names, peer identifiers and conversations are fictional. The renderer does
not start the MIDlet, open an RMS profile, use a Telegram account or access the
network.

Regenerate the images with:

```powershell
.\tools\render-showcase.ps1
```
```bash
pwsh -File tools/render-showcase.ps1
```

Regenerate them on the platform they were last rendered on. Text is rasterised
through AWT, which is platform-dependent, so a rerun on a different operating
system produces visually different PNGs even with no code change. The committed
set was rendered on Windows.
