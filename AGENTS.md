# Last Meeting - Project Specification & Technical Decisions

## Overview
"Last Meeting" is an Android application designed to record meeting audio, transcribe it, and generate protocol summaries using any OpenAI-compatible multimodal API endpoint (e.g., OpenAI, OpenRouter, Gemini, Groq).

---

## 1. Confirmed Decisions & Specifications

### Audio Format & Storage
- **Audio Format**: `M4A` (AAC audio encoding in MPEG-4 container).
- **Storage**: App-private storage (`context.filesDir/recordings/meeting_YYYYMMDD_HHMMSS.m4a`).
- **Database**: Room Database storing meeting records:
  - `id` (Long / Primary Key)
  - `title` (String, defaults to localized timestamp string, e.g., "Meeting 2024-11-24 14:30")
  - `startTime` (Long, epoch milliseconds)
  - `endTime` (Long, epoch milliseconds)
  - `audioFilePath` (String)
  - `transcript` (String, nullable)
  - `protocol` (String, nullable)
  - `createdAt` (Long)

### AI Integration (Multimodal Chat Completions)
- **API Standard**: Strictly use `/v1/chat/completions` (OpenAI Multimodal Chat Completions API format).
- **Audio Payload**: Audio encoded as base64 in `input_audio` content payload (`type: "input_audio", input_audio: { data: "<base64>", format: "m4a" }`).
- **Configuration (DataStore Preferences)**:
  - `baseUrl` (default: `https://api.openai.com/v1/`)
  - `apiKey`
  - `model` (default e.g., `gpt-4o-audio-preview` or configurable multimodal model)
  - `transcriptionSystemPrompt` (default provided, customizable by user)
  - `protocolSystemPrompt` (default provided, customizable by user)

### UI & Navigation
- **Main Screen**: Toggle button to switch between **List View** and **Calendar View**.
- **Meeting Details View**:
  - Edit title
  - Trigger audio transcription via AI
  - Trigger protocol creation via AI
  - Read transcript / protocol
  - Download audio, transcript (PDF), or protocol (PDF)
  - Share audio, transcript (PDF/text), or protocol (PDF/text)
  - Delete transcript individually (with confirmation dialog)
  - Delete protocol individually (with confirmation dialog)
  - Delete audio (deletes the entire meeting record and associated files, with confirmation dialog)
- **Settings View**: Configure API Base URL, API Key, Multimodal Model Name, and System Prompts.

### Export & Sharing
- **PDF Export**: Generated dynamically using Android `android.graphics.pdf.PdfDocument` and saved to `Downloads/LastMeeting/` via MediaStore.
- **Sharing**: Android Sharesheet (`Intent.ACTION_SEND` with `FileProvider`) for audio file, transcript, or protocol.

### Background Services & Screen Retention
- **Audio Recording**: Android `ForegroundService` with notification and `foregroundServiceType="microphone"`. Recording will not stop when the screen turns off or the app is minimized.
- **AI Processing (Transcribing & Protocolizing)**: Background WorkManager / Service tasks with proper lifecycle management so network requests finish reliably when screen turns off.

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
