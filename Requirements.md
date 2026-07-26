# Infinite-Recorder App Specification

## Product Purpose

Infinite-Recorder is a private, voice-focused activity journal for Android. It records the user's day so the audio can later be converted into timestamped text and used to reconstruct what the user was working on.

The first version focuses on reliable capture, organization, playback, and export. Automatic transcription is a future capability and is not required for the first version.

- **Primary target device:** Pixel 9a
- **Current version:** 1.1.0 (`versionCode` 5)
- **Distribution:** Personal sideloading
- **Minimum SDK:** 26
- **Language:** Kotlin
- **UI:** XML layouts with View Binding
- **Screen orientation:** Portrait only for every app-owned activity
- **Auto-start on boot:** No
- **Privacy:** Local-only, with no internet, analytics, advertising, or tracking

## Core Behavior

- The user opens the app and taps **Start** to begin a recording session.
- Recording continues until the user pauses or stops it, an unrecoverable error occurs, or the 5 GB storage limit requires intervention.
- The app automatically finalizes a new `.m4a` segment at the configured interval and begins the next segment without losing or duplicating retained audio. Supported intervals are 15, 30, 60, and 120 minutes; the default is 60 minutes.
- Segment rotation must use a continuous audio-capture pipeline. Stopping and restarting microphone capture at each segment boundary is not acceptable.
- The AAC encoder must also remain continuous across a normal segment rotation. The first encoded-frame timestamp of the new segment is the exact session-audio end offset of the preceding segment.
- **Pause** suspends capture. **Resume** continues writing to the same segment, with paused time omitted from the audio.
- While paused, the saved-audio duration must not advance. After resume, the UI and notification must leave the Paused state, retain the same current filename, and continue advancing the saved-audio duration.
- **Stop** finalizes the current segment and ends the session.
- Recording continues while the screen is off or the app is not in the foreground.
- Recording never starts automatically after boot or without explicit user action.
- If a recording crosses local midnight, finalize the current segment and begin a new daily session so recordings remain organized by calendar day.

## Pocket Safety and Control Protection

- Provide **Pocket Lock**, enabled by default, to prevent incidental touches from pausing or stopping an active session.
- When Pocket Lock is enabled:
  - the Home-screen **Pause** and **Stop** controls require an uninterrupted press-and-hold of at least two seconds;
  - releasing or moving outside the control before two seconds must cancel the action;
  - completing the hold must produce haptic feedback and open a confirmation dialog;
  - recording must continue while either confirmation dialog is displayed;
  - only an explicit confirmation may pause or stop the session; and
  - **Start**, **Resume**, and **Bookmark** remain immediate because they cannot silently end capture.
- Show a prominent Home-screen indicator explaining whether Pocket Lock is on and how protected controls work.
- Allow Pocket Lock to be disabled in Settings, with an explicit warning that Pause and Stop will then respond immediately.
- Do not expose direct **Pause** or **Stop** service actions in the ongoing notification. Tapping the notification opens the Home screen, where Pocket Lock applies.
- The notification may retain the non-destructive **Bookmark** action and may provide **Resume** while manually paused.
- Recommend locking the phone with its power button after starting a session. Recording must continue while the phone is locked and the screen is off.
- Pocket Lock protects against incidental screen and notification touches; it cannot prevent battery exhaustion, device restart, Android Force Stop, revoked microphone permission, hardware failure, or exhausted storage.

## Daily Sessions and Timeline

- Group all recording segments by local calendar day.
- Show both:
  - elapsed wall-clock session time; and
  - actual saved-audio duration after pauses and suppressed silence.
- Preserve the real-world time represented by every part of the saved audio.
- Store timestamps in UTC together with the local time-zone identifier and UTC offset so daylight-saving and time-zone changes remain unambiguous.
- Provide a **Bookmark** action on the Home screen and recording notification.
- A bookmark records its real-world timestamp, session identifier, current segment, and audio offset.
- Allow an optional short label for a bookmark without interrupting recording.
- Encourage brief spoken activity markers, such as “starting work on project X,” because silent computer activity cannot be inferred from audio alone.

## Audio

- **Container:** MPEG-4 audio (`.m4a`)
- **Codec:** AAC-LC
- **Capture format:** 16 kHz, 16-bit, mono PCM before encoding
- **Default bitrate:** approximately 64 kbps
- **Channel configuration:** mono, optimized for voice
- **Source:** device microphone
- Supported bitrates are 32, 64, 96, and 128 kbps.
- Each `.m4a` segment must be independently playable and usable by common transcription tools.
- All audio selected for retention must be encoded exactly once, without an intentional gap or duplicate at segment boundaries.
- A rotation may occur only on an encoded AAC frame boundary. Metadata rounding must not imply a gap: the preceding segment's `sessionAudioEndOffsetMs` must equal the next segment's `sessionAudioOffsetMs`.
- Validate boundary handling with a continuous test signal that spans a forced one-minute segment rotation.

## Silence Suppression

- Provide optional **Silence Suppression**, enabled by default.
- While a session is active, microphone capture and sound analysis remain active even when the app is not writing audio. The app cannot detect new sound if it completely turns off the microphone.
- Silence suppression reduces saved file size; it does not eliminate microphone, foreground-service, or battery usage.
- Detect sustained silence using an adaptive noise floor and configurable sensitivity rather than a single device-independent amplitude value.
- Calibrate the initial room-noise floor for approximately one second while preserving that interval in pre-roll. Loud sound must still be detectable during calibration.
- Require multiple active analysis frames within a short window before declaring sound. An isolated click or amplitude spike must not cause buffered quiet audio to be retained.
- Default behavior:
  - retain approximately 2 seconds of pre-roll before detected sound;
  - continue recording through short pauses in speech;
  - stop writing only after approximately 5 seconds of sustained silence; and
  - retain approximately 1 second of trailing audio.
- Detection must operate on raw captured audio before AAC encoding.
- The pre-roll buffer must prevent the beginning of speech or other detected sounds from being clipped.
- Silence suppression must not create a new file every time sound resumes. Resume writing to the current segment.
- Record every omitted-silence interval in session metadata so saved-audio offsets can be mapped back to real clock time.
- Treat manual Pause differently from automatically suppressed silence and identify both separately in metadata.
- Provide three simple sensitivity choices—Low, Medium, and High—with Medium as the default.
- Explain that constant background noise, fans, music, traffic, or distant conversation may prevent silence suppression from activating.
- Provide a continuous-recording mode that disables silence suppression.
- If a session ends without any retained PCM audio, do not publish an AAC priming-only `.m4a`; remove the empty session metadata and report zero saved audio/storage.

## File Management and Shared Storage

- Recordings and session metadata must use shared device storage so they:
  - remain after Infinite-Recorder is uninstalled;
  - are visible in the Android Files app; and
  - can be opened, copied, or shared with other apps.
- **Default folder:** `Download/Infinite-Recorder`
- On Android 10 and later, create files through `MediaStore.Downloads`. Android permits this collection to create app-owned content under shared `Download`, but not under `Documents`; therefore `Documents/Infinite-Recorder` is not a valid default for this implementation.
- The `Download/Infinite-Recorder` location remains visible in Files and survives app uninstall without requiring broad storage permission or a folder picker.
- On Android 8 and 9, use the legacy public Download directory and limit `WRITE_EXTERNAL_STORAGE` to SDK 28 and earlier.
- Organize content into daily subfolders named `YYYY-MM-DD`.
- Use Android scoped-storage APIs. Do not request broad file-management access.
- **Audio filename:** `recording_YYYY-MM-DD_HH-MM-SS.m4a`
- **Session metadata filename:** `session_YYYY-MM-DD.json`
- Files are finalized at the configured segment interval, at local midnight, and when the user taps Stop.
- On Android 10 and later, create an active audio row with `IS_PENDING=1`, write and finalize the MPEG-4 container, then publish it with `IS_PENDING=0`. Only published files are normal completed recordings.
- A partially completed segment must be finalized whenever technically possible after an error or unexpected termination.
- Never expose an incomplete file as a successfully completed recording.
- On startup, detect stale pending audio rows left by an interrupted process. Preserve them with a `.partial.m4a` filename, publish them as `application/octet-stream`, and clearly identify them as incomplete rather than silently deleting them or presenting them as playable completed audio.

## Session Metadata and Transcription Readiness

Maintain a human-readable JSON manifest in shared storage for each daily session. It must remain available after app uninstall and contain:

- schema version and stable session identifier;
- session start and end in UTC and local time;
- time-zone identifier and UTC offsets;
- ordered audio segment filenames, byte sizes, durations, and capture-time ranges;
- mapping between saved-audio offsets and real-world timestamps;
- manual pause/resume intervals;
- automatically suppressed-silence intervals;
- bookmarks and bookmark labels;
- recording interruptions and errors;
- file finalization and recovery status; and
- user-managed processing status: **Unprocessed**, **Processed**, or **Keep**.

Metadata updates must be crash-safe. Commit each update to an app-private `AtomicFile` mirror while the app is installed, then refresh the shared daily JSON copy after each material timeline event. When recovering, accept only well-formed metadata for the expected day and preserve the last valid private copy if a shared update was interrupted.

The Recordings screen must allow sharing all audio segments and the JSON manifest for a selected day as one multi-file share operation. This provides a stable future input for computer-based or in-app transcription.

Transcription tools must be able to decode the saved files directly. Converting audio to text must not require playing it through the phone speaker, and Share/Open must not begin audible playback automatically.

## Storage Limit and Retention

- **Maximum total storage managed by Infinite-Recorder:** 5 GB (`5,000,000,000` bytes).
- Count completed audio, the active segment, metadata, and recoverable partial files toward the limit.
- Before creating a segment, reserve its estimated maximum size using the selected bitrate and interval, with a safety allowance for container overhead. Do not start a segment that cannot safely fit under the limit.
- Display current usage and estimated remaining recording time.
- New recordings default to **Unprocessed**.
- Never automatically delete:
  - the active segment;
  - a file currently being played, opened, or shared;
  - an **Unprocessed** daily session; or
  - a session marked **Keep**.
- When more space is required, automatically delete the oldest completed sessions marked **Processed**, if available.
- Recalculate actual MediaStore usage after each automatic deletion before deciding whether another day must be removed.
- Deletion must operate on a whole daily session, including its audio and metadata, and must be clearly reported.
- If the limit is reached and no eligible processed session can be deleted, finalize the active file when possible, stop recording, and show a prominent error asking the user to process, export, or delete recordings.
- Manual deletion always requires confirmation.
- Never imply that sharing a recording means it was successfully transcribed or safely archived.

At approximately 64 kbps, 5 GB provides roughly 170 hours of continuously saved audio. Silence suppression may extend the wall-clock retention period substantially.

## Permissions and Android Service Requirements

- `RECORD_AUDIO`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MICROPHONE`
- `WAKE_LOCK` for a partial wake lock while actively monitoring or recording
- `POST_NOTIFICATIONS` where required by the Android version
- Declare the recording foreground service with the `microphone` service type.
- Start microphone capture only from an explicit user action while the app is eligible to access the microphone.
- Show an ongoing foreground-service notification throughout an active or paused session.
- The notification must never provide direct **Pause** or **Stop** actions. It may provide **Bookmark** while active or paused and **Resume** while paused.
- Do not request `READ_EXTERNAL_STORAGE`. Declare `WRITE_EXTERNAL_STORAGE` only with `maxSdkVersion="28"` for Android 8 and 9 compatibility; it must not be requested on the Pixel 9a.
- Do not request `MANAGE_EXTERNAL_STORAGE`.
- **No Internet permission.**

## Screens

- `MainActivity`, `RecordingsActivity`, and `SettingsActivity` must remain locked in portrait orientation. App-owned screens must not rotate to landscape when the phone is turned.
- External system UI launched by Share or Open is controlled by Android and is outside the app's orientation requirement.
- Every app screen must consume Android status-bar, display-cutout, and navigation-bar `WindowInsets`. Do not use a hard-coded top offset. Titles and controls must remain below the Pixel 9a status icons and centered camera lens, and above the navigation area.

### 1. Home Screen

- Large, state-aware **Start**, **Pause/Resume**, and **Stop** controls
- **Bookmark** action
- Status: Idle, Listening, Recording Sound, Silence Suppressed, Paused, Stopping, or Error
- Session wall-clock duration
- Saved-audio duration
- Current filename
- Current sound level indicator
- Total storage used out of the 5 GB limit
- Estimated saved-audio time remaining at the selected bitrate
- Prominent recording, microphone, recovery, and storage errors
- Prominent Pocket Lock state and protected-control instructions
- Navigation to Recordings and Settings
- Refresh visible state from both service broadcasts and the persisted runtime snapshot so activity recreation or missed OEM broadcast delivery does not leave stale controls.

### 2. Recordings Screen

- Group recordings by daily session.
- Show the total completed-file count and size.
- Show each day's date, file count, total size, and processing status.
- Show each completed file's name, local start time, media duration, and size.
- Play, pause, and seek within one completed `.m4a` file at a time. Release playback when the activity closes or the playing file is deleted.
- Clearly label partial files and warn that they may be unplayable.
- Open a recording in a compatible external app.
- Share one recording or an entire day through Android's system share sheet.
- A day share must include its completed audio files and shared JSON manifest when available.
- Mark a daily session **Unprocessed**, **Processed**, or **Keep**.
- Delete an individual recording, a whole day, or all completed recordings only after an explicit confirmation dialog.
- When one file is deleted but its day remains, mark that segment Deleted in the daily metadata rather than leaving it recorded as Completed.
- When the last audio file for a day is deleted, remove both the shared daily JSON and its app-private metadata mirror so storage returns to zero and the deleted session cannot reappear.
- Deleting a whole day removes its audio and JSON manifest.
- Never list a pending active recording as completed or include it in a destructive bulk action.

Continuous cross-segment day playback and a live real-world timestamp display are future enhancements. Version 1 metadata must preserve enough offset and clock information to add them later without changing existing recordings.

### 3. Settings Screen

- Segment interval: 15, 30, 60, or 120 minutes, defaulting to 60 minutes
- AAC bitrate: 32, 64, 96, or 128 kbps, defaulting to 64 kbps
- Silence Suppression on/off, defaulting to on
- Silence sensitivity: Low, Medium, or High
- Pocket Lock on/off, defaulting to on
- Display the fixed 5 GB storage limit
- Explain retention rules and which processed recordings may be automatically deleted
- Display a concise privacy reminder about recording nearby people

## Reliability and Error Handling

- If device storage becomes full, finalize the active segment when possible, stop recording, and prominently report the error.
- If Android terminates the service or process, preserve and finalize the current file whenever technically possible, report the interruption when the app next opens, and mark the matching daily session `Interrupted`.
- Unexpected-termination metadata must include the detection time in UTC and local time, the last known saved-audio duration and current filename when available, a typed error entry, and an interrupted/recovery-pending state for any segment previously marked `Writing`.
- Empty-manifest cleanup must preserve an interrupted session or a session containing errors or bookmarks even when it has no completed audio. Explicit deletion of the final recording for a day may still remove that day's metadata after confirmation.
- If microphone access is lost, revoked, muted by the system, or fails, finalize the active segment when possible, stop recording, and prominently report the cause.
- Persist a runtime snapshot containing recorder state, session start, pause timing, saved duration, current filename, sound level, storage use, and any error. Broadcast state changes for responsiveness and poll the snapshot while the Home screen is visible as a consistency fallback.
- Every storage rescan and completed deletion must update the persisted storage total as well as the visible screen. A periodic runtime-state poll must not restore a stale pre-deletion value.
- If persisted state claims recording is active but the service is no longer running, show an interruption error and begin partial-output recovery rather than displaying a false active state.
- Resume must explicitly publish the new non-paused state; advancing audio while the UI or notification remains Paused is a failure.
- Do not silently claim that recording continued through an interruption.
- Do not silently discard, overwrite, or corrupt a completed recording.
- Hold a partial wake lock while actively capturing or monitoring sound, but not while manually paused or idle.
- If silence detection fails, prefer retaining extra audio over losing potentially meaningful speech.
- Test long-running capture with the screen off, activity recreation, forced segment boundaries, local midnight, low storage, process termination, and permission revocation.
- Test every app-owned activity in portrait with the Pixel 9a upright and physically rotated. Verify that the top title clears the clock, status icons, and camera cutout and that bottom controls clear the navigation area.

## Main Components

1. `MainActivity` — state-aware UI and service control
2. `RecordingService` — microphone foreground service and notification controls
3. `RecordingManager` — continuous raw audio capture, AAC encoding, gapless segment rotation, pause/resume, and finalization
4. `AacSegmentWriter` — one continuous AAC encoder with independently finalized MPEG-4 segment muxers
5. `SoundActivityDetector` — adaptive sound/silence classification and pre-roll buffering
6. `SessionTimeline` — time mapping, silence/pause intervals, bookmarks, and crash-safe daily metadata
7. `SharedStorageRepository` — MediaStore output, indexing, partial recovery, storage accounting, retention, and deletion
8. `RecordingsActivity` — grouped listing, per-file playback/seek, Open, Share, processing status, and confirmed deletion
9. `SettingsRepository` — local preferences, Pocket Lock default, and fixed 5 GB limit
10. `SystemBarInsets` — reusable status-bar, camera-cutout, and navigation-area inset handling
11. `PocketProtectionPolicy` — testable rules for protected Pause and Stop controls

## Version 1 Acceptance Checks

- `testDebugUnitTest`, `lintDebug`, and `assembleDebug` must all pass before installation.
- Install the resulting debug APK on the Pixel 9a and verify version/package metadata and required runtime permissions.
- Confirm that all three app activities stay in portrait and remain clear of status, cutout, and navigation insets.
- Record real microphone audio, pause for a measured interval, resume, and stop. The filename must stay unchanged across pause/resume, saved duration must remain fixed while paused, and the JSON pause duration must reflect the omitted interval.
- Temporarily force a one-minute segment interval and record across the boundary. Both files must finalize as independently playable audio, the microphone/encoder pipeline must remain continuous, and the first segment's audio end offset must exactly equal the second segment's audio start offset.
- Confirm completed files appear under `Download/Infinite-Recorder/YYYY-MM-DD`, are published with `IS_PENDING=0`, and can be discovered by Files and other apps.
- Confirm per-file playback and seeking, Open and Share intents, day sharing, processing-status changes, and confirmation dialogs for individual/day/all deletion.
- Delete the final file for a day and verify that its shared/private metadata is removed and the Home screen reports zero managed storage without requiring an app restart.
- Start and stop a session in a quiet room without speaking. Verify that saved duration stays zero and no `.m4a` or orphan daily metadata is published.
- Verify with synthetic detector input that one loud frame is rejected, steady room noise is learned during calibration, and sustained speech above that floor is retained.
- Simulate or detect a stale pending output and verify that it is preserved and visibly marked partial.
- With Pocket Lock on, verify that a short tap cannot pause or stop; a two-second hold opens confirmation; cancel keeps recording; and explicit confirmation performs the requested action.
- Verify that moving outside a protected control before two seconds cancels the hold and that protected controls produce haptic feedback only after the hold completes.
- Verify the active notification offers Bookmark but no Pause or Stop action. While paused, verify it offers Resume and Bookmark but no Stop action.
- Lock the Pixel 9a with the power button during active capture, keep it in a pocket with the screen off, then unlock and confirm the session remained active.
- Simulate stale active runtime state with no running service. Verify a prominent interruption error, partial-output recovery, and an `UnexpectedTermination` entry in the matching daily JSON manifest.
- After clean app-data reset, verify the defaults: portrait UI, 64 kbps, 60-minute segments, Medium sensitivity, silence suppression on, Pocket Lock on, and a 5 GB limit.
- Verify that the installed package does not request the Internet permission.

## Privacy

- All audio, metadata, and settings remain local to the device unless the user explicitly shares or opens them in another app.
- No networking, cloud backup integration, tracking, analytics, advertising, or telemetry.
- No automatic recording at boot.
- Keep the persistent Android microphone indicator and foreground-service notification visible whenever the microphone is active.
- Clearly remind the user that nearby people may be recorded and that the user is responsible for following applicable consent and privacy rules.
