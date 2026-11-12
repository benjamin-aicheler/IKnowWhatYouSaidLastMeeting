# Last Meeting - Project Specification & Technical Decisions

## Overview
"Last Meeting" is an Android application designed to record meeting audio, transcribe it, and generate protocol summaries using OpenAI or any OpenAI-compatible API endpoint (e.g., OpenAI, OpenRouter, Gemini, Groq).

---

## 1. Confirmed Decisions & Specifications

### Audio Format & Storage
- **Audio Format**: `M4A` (AAC audio encoding in MPEG-4 container, 32 kbps at 16 kHz mono so 1 hour is ~14.4 MB and stays below the 25 MB upload limit).
- **Storage**: App-private storage (`context.filesDir/recordings/meeting_YYYYMMDD_HHMMSS.m4a`).
- **Database**: Room Database storing meeting records:
  - `id` (Long / Primary Key)
  - `title` (String, defaults to localized timestamp string, e.g., "Meeting 2024-11-24 14:30")
  - `startTime` (Long, epoch milliseconds)
  - `endTime` (Long, epoch milliseconds; `0` while the recording is still running)
  - `audioFilePath` (String)
  - `transcript` (String, nullable)
  - `protocol` (String, nullable)
  - `createdAt` (Long)
  - `transcriptGeneration` / `protocolGeneration` (Long, default `0`; persisted invalidation versions)

### Recording Lifecycle
- The meeting row is inserted as soon as the recorder runs, not after it stops, so an interrupted recording keeps a meeting instead of leaving an audio file nothing points at.
- `endTime = 0` marks a running recording. `getAllMeetings()` filters `endTime > 0`, so a running recording never appears in the list and cannot be deleted while the microphone is open.
- `MeetingRepository.finishMeeting()` closes the row on stop. The insert is kept as a `Deferred` and awaited rather than matched back by file path — a slow insert must not produce a second row for the same file. If the insert failed, stop falls back to `createMeeting()`; a recording that produced no usable audio discards its row.
- `reconcileRecordings()` runs in `LastMeetingApp.onCreate`: unfinished rows are closed from the audio file's last modification time, rows whose file vanished are deleted, and `.m4a` files without a row are adopted (visible and deletable). It never deletes files from storage and is idempotent.

### Backup
- `android:allowBackup="false"` deliberately disables cloud backup: the settings DataStore holds the API key, recordings are private and can exceed the 25 MB backup quota, and a partial restore would leave meeting rows without their audio.
- `android:dataExtractionRules="@xml/data_extraction_rules"` explicitly excludes every storage domain from Android 12+ cloud backup and device-to-device transfer; some manufacturers permit device transfer despite `allowBackup="false"`. `android:fullBackupContent="@xml/backup_rules"` supplies matching exclusions for Android 11 and lower. Keep all nine domain exclusions in each policy: `root` alone does not cover the independently traversed `file` and `database` domains. This excludes recordings, the Room database (including transcripts/protocols), and the settings DataStore/API key.
- The API key is stored unencrypted in app-private storage. Keystore encryption was considered and dropped: it only guards against extraction on a rooted device, cannot be verified in CI (Robolectric has no `AndroidKeyStore`), and adds key-loss and migration risk.

### AI Integration
- **Transcription**:
  - **OpenAI (`api.openai.com`)**: Uses `POST /v1/audio/transcriptions` (`multipart/form-data`) sending the `.m4a` recording directly with `model = transcriptionModel` and `prompt = transcriptionSystemPrompt`.
  - **Custom Multimodal Endpoints**: Uses `POST /v1/chat/completions` with audio encoded as base64 in `input_audio` content payload (`type: "input_audio", input_audio: { data: "<base64>", format: "m4a" }`).
- **Protocol Generation**:
  - Uses `POST /v1/chat/completions` with `model = protocolModel`, sending **text only** (`protocolSystemPrompt` and the meeting's existing `transcript` text). Protocol generation requires a transcript to exist first and never re-uploads the audio file.
- **Configuration (DataStore Preferences)**:
  - `baseUrl` (default: `https://api.openai.com/v1/`)
  - `apiKey`
  - `transcriptionModel` (default: `gpt-4o-mini-transcribe`)
  - `protocolModel` (default: `gpt-4o-mini`)
  - `transcriptionSystemPrompt` (default provided, customizable by user)
  - `protocolSystemPrompt` (default provided, customizable by user)

### UI & Navigation
- **Main Screen**: Toggle button to switch between **List View** and **Calendar View**.
- **Meeting Details View**:
  - Edit title
  - Trigger audio transcription via AI
  - Confirm before replacing an existing transcript; explain the additional API request and protocol removal if the text changes. Keep existing results until transcription succeeds. Atomically clear the protocol and advance `protocolGeneration` only when the stored transcript changes, so older protocol work cannot repopulate it.
  - Trigger protocol creation via AI (based on the text transcript; prompts the user to transcribe first if no transcript exists yet)
  - Read transcript / protocol
  - Download audio, transcript (PDF), or protocol (PDF)
  - Share audio, transcript (PDF/text), or protocol (PDF/text)
  - Delete transcript individually (with confirmation dialog)
  - Delete protocol individually (with confirmation dialog)
  - Delete audio (deletes the entire meeting record and associated files, with confirmation dialog)
- **Settings View**: Configure API Base URL, API Key, Transcription Model Name, Protocol Model Name, and System Prompts.

### Export & Sharing
- **PDF Export**: Generated dynamically using Android `android.graphics.pdf.PdfDocument` and saved to `Downloads/LastMeeting/`. API 29+ writes through MediaStore; API 26-28 writes to the public Downloads folder directly and therefore needs `WRITE_EXTERNAL_STORAGE` (`android:maxSdkVersion="28"`), requested by the export action in `MeetingDetailsScreen` before the exporter runs.
- **Export integrity**: PDF and audio use the same Downloads writer. API 29+ inserts with `IS_PENDING=1`, publishes only after the stream closes successfully, and attempts to delete its own entry on handled failure. API 26-28 atomically reserves a unique filename and removes only that incomplete export on failure. Names preserve Unicode letters, numbers, and combining marks with NFC normalization and bounded UTF-8 length. Shared cache PDFs remain unique and retain earlier contents.
- **Sharing**: Android Sharesheet (`Intent.ACTION_SEND` with `FileProvider`) for audio file, transcript, or protocol.

### Background Services & Screen Retention
- **Audio Recording**: Android `ForegroundService` with notification and `foregroundServiceType="microphone"`. Recording will not stop when the screen turns off or the app is minimized.
- **AI Processing (Transcribing & Protocolizing)**: Background WorkManager / Service tasks with proper lifecycle management so network requests finish reliably when screen turns off. The worker runs in the foreground where the platform allows it.
- **Retries**: `AiRetryPolicy` retries HTTP 429, 5xx and network failures, at most three runs with 30 s/60 s exponential backoff. Other 4xx, configuration and serialization failures fail immediately. There is no second retry loop in the HTTP client, so attempts are not multiplied.
- **Deleting AI results**: Deleting a transcript or protocol atomically clears that field and increments its generation. AI work captures the generation when enqueued, retains it across retries, and writes only if that generation still matches. Work names include the generation so cancelling stale work cannot cancel or block a new explicit request. Stale results and legacy jobs without a generation are discarded quietly. Room migration 1→2 preserves existing meetings and initializes both generations to `0`.

### Localization & Multi-Language Support
- **Languages Supported**: English (`res/values/strings.xml`) and German (`res/values-de/strings.xml`).
- All user-visible strings, dates, and default prompts will be localized.

### Tech Stack
- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Architecture**: MVVM with Repository pattern
- **Database**: Room
- **Preferences**: Jetpack DataStore
- **HTTP Client**: Ktor / OkHttp
- **Dependency Injection**: Hilt / Kotlin-inject or ViewModel Factory

---

## 2. Git & GitHub Workflow Rules

1. **Branching**:
   - Always update before starting: `git checkout main && git pull origin main`
   - Create isolated feature branches: `ai/feature/issue-<number>-<description>` or `ai/feature/<description>`
2. **Commits**:
   - Follow Conventional Commits: `feat:`, `fix:`, `docs:`, `refactor:`
   - Keep commits small, atomic, and focused.
3. **Build & Pull Requests**:
   - Verify build locally (`./gradlew assembleDebug` or `./gradlew build`) before pushing.
   - Push branch (`git push -u origin <branch-name>`) and create PR (`gh pr create`).

## 3. Local Build Environment

- **JDK**: Java 21 is required by `gradle/gradle-daemon-jvm.properties`. A system OpenJDK 21 on `PATH` is enough; set `JAVA_HOME` only when several JDKs are installed.
- **Android SDK**: Point `ANDROID_HOME` at the installed SDK or set `sdk.dir` in an untracked `local.properties`. Keep the SDK outside `/tmp` and the project's `build/` directory so it survives `gradlew clean`.
- **Gradle cache**: `GRADLE_USER_HOME` defaults to `~/.gradle`; keep the distribution and dependency caches there so builds stay incremental across checkouts.
- **Repository helper**: `./scripts/build-local.sh` forwards its arguments to the Gradle wrapper and runs `assembleDebug testDebugUnitTest lintDebug` when called without arguments. It works from any directory and sources an optional profile at `.build.env` in the checkout root.
- **Local profile**: put machine-specific values such as `ANDROID_HOME` or `GRADLE_USER_HOME` in `.build.env`. It is git-ignored; do not commit it or the tool binaries it points to.
- AGP resolves its own `aapt2` artifact per host architecture, so no resource-compiler override is needed on Linux ARM64 or x86_64.
- Prefer incremental builds; run `clean` only when a failure or task warrants it, and run the failing Gradle task alone when troubleshooting.
