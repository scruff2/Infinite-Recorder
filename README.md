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

Recording begins only from the visible app after microphone permission is granted. Silence suppression keeps monitoring the microphone but omits sustained quiet audio from the saved file.

Pocket Lock is enabled by default. Home-screen Pause and Stop require a two-second hold followed by confirmation. The ongoing foreground-service notification provides Bookmark and, while paused, Resume; it does not expose direct Pause or Stop actions.

## Microphone sensitivity

Settings provides Low, Medium, and High sensitivity choices. These control the sound-detection threshold used by silence suppression; they do not change the Pixel's physical microphone gain or the volume of retained audio.

- **Low:** Retains only louder or closer sounds and omits more background audio.
- **Medium:** Default balance for ordinary voice activity.
- **High:** Retains quieter or more distant sounds, but may also retain more pocket noise and background sound.

Sensitivity is read when a new recording session starts, so a change applies to the next session. Infinite Recorder captures from Android's standard microphone source without applying digital amplification. Android and the Pixel hardware control input gain. Adding software amplification would also amplify noise and could cause clipping.

See [Requirements.md](Requirements.md) for the complete specification.
