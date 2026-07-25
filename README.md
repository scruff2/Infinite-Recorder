# Infinite Recorder

Infinite Recorder is a local-only Android activity-journal recorder designed for a Pixel 9a. It captures mono AAC audio into independently playable `.m4a` segments, omits sustained silence when enabled, and keeps timestamp metadata for later transcription.

The app stays in portrait orientation and accounts for the Pixel 9a status bar, centered camera cutout, and navigation area.

## Privacy

- No Internet permission
- No analytics, tracking, advertising, or cloud integration
- Recordings remain in shared device storage after uninstall
- Audio leaves the device only through an explicit Share/Open action

## Build

Requirements:

- JDK 17
- Android SDK 36

From PowerShell:

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug
```

The debug APK is written to:

`app\build\outputs\apk\debug\app-debug.apk`

## Device behavior

Recording begins only from the visible app after microphone permission is granted. An ongoing foreground-service notification provides Pause/Resume, Bookmark, and Stop controls. Silence suppression keeps monitoring the microphone but omits sustained quiet audio from the saved file.

See [Requirements.md](Requirements.md) for the complete specification.
