# Last Meeting 🎙️

**Last Meeting** is a modern Android app built with **Jetpack Compose** and **Material 3** to record meeting audio, automatically transcribe it, and generate structured protocol summaries using OpenAI or any OpenAI-compatible API endpoint.

---

## ✨ Features

- 🎙️ **Persistent Audio Recording**:
  - Records speech-optimized `M4A` (AAC, 32 kbps / 16 kHz mono, ~14.4 MB/hour so a full 1-hour meeting stays well below the 25 MB upload limit).
  - Powered by an Android **Foreground Service** with persistent notification and microphone service type—recording continues uninterrupted when the screen turns off or the app is minimized.
  - Crash-safe: the meeting is saved when recording starts, so an interrupted recording is recovered on the next app start instead of leaving an unreachable audio file behind.

- 🤖 **AI Transcription & Protocolization**:
  - Transcribes `.m4a` recordings directly via OpenAI's `/v1/audio/transcriptions` endpoint, while preserving `/v1/chat/completions` (`input_audio` base64 `m4a`) for custom multimodal endpoints (e.g., **OpenRouter**, **Gemini**).
  - Generates structured protocols from the **text transcript** via `/v1/chat/completions`—audio is only uploaded once.
  - Separate configurable models and system prompts for transcription (`gpt-4o-mini-transcribe` default) and executive protocol creation (`gpt-4o-mini` default).

- 📅 **List & Calendar Views**:
  - Toggle seamlessly between a sorted **List View** and an organized **Calendar View** on the main screen.

- 📝 **Meeting Details & Management**:
  - Custom meeting titles (defaults to timestamp e.g. "Meeting 2024-11-24 14:30").
  - In-app audio player to listen to recordings.
  - Read transcript and protocol text directly in the app.

- 📄 **Export & Share**:
  - Export transcript or protocol as a cleanly formatted **PDF** directly to the public `Downloads/LastMeeting` folder (Android 8/9 asks for storage access once before the first export).
  - Share audio, transcript (PDF or text), or protocol (PDF or text) via the native Android Sharesheet.

- 🗑️ **Permanent & Individual Deletions**:
  - Delete transcript or protocol individually.
  - Delete audio recording (permanently removes the entire meeting record and local audio file from disk).
  - All deletion actions require explicit confirmation dialogs.

- 🌍 **Localization**:
  - Full multi-language support starting with **English** (`values/strings.xml`) and **German** (`values-de/strings.xml`).

---

## 🛠️ Tech Stack & Architecture

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose + Material 3
- **Navigation**: Jetpack Navigation Compose
- **Architecture**: MVVM with Repository pattern
- **Database**: Room Database (local storage for meeting records)
- **Preferences**: Jetpack DataStore Preferences (API key and prompt settings in app-private storage, excluded from cloud backup and device transfer — the key is stored unencrypted)
- **HTTP Client**: OkHttp 5 with `kotlinx.serialization`
- **Background Processing**: Android Foreground Service (recording) & WorkManager (AI transcription/protocolization)
- **PDF Generation**: Native `android.graphics.pdf.PdfDocument` & `MediaStore`

---

## 🚀 Getting Started

### Prerequisites

- **Android Studio**: Latest version with Android Gradle Plugin (AGP) 9.5+ support
- **JDK**: Java 21 (required by the Gradle daemon toolchain configuration)
- **Android SDK**: `compileSdk = 37`, `targetSdk = 36`, `minSdk = 26`
- **Android Device / Emulator**: Android 8.0 (API level 26) or higher

### Building the App

1. Clone the repository:
   ```bash
   git clone https://github.com/benjamin-aicheler/IKnowWhatYouSaidLastMeeting.git
   cd IKnowWhatYouSaidLastMeeting
   ```
2. Open the project in Android Studio.
3. Build the debug APK:
   ```bash
   ./gradlew assembleDebug
   ```

### Local Build Helper

`./scripts/build-local.sh` forwards its arguments to the Gradle wrapper and, without
arguments, runs `assembleDebug testDebugUnitTest lintDebug`. It works from any
directory and sources an optional machine-specific profile at `.build.env` in the
checkout root:

```bash
export ANDROID_HOME="$HOME/.local/share/android-sdk"
export GRADLE_USER_HOME="$HOME/.gradle"
```

`ANDROID_HOME` and `GRADLE_USER_HOME` are standard Gradle settings, so the profile is
optional when your shell already exports them. Alternatively, set `sdk.dir` in an
untracked `local.properties` inside the checkout. Keep the profile out of Git.

```bash
./scripts/build-local.sh                    # APK, unit tests, and lint
./scripts/build-local.sh assembleDebug      # APK only
```

---

## ⚙️ Configuration & Settings

To use AI transcription or protocolization:

1. Open the app and tap the **Settings** icon (⚙️) in the top right.
2. Enter your **OpenAI Compatible Base URL** (default: `https://api.openai.com/v1/`).
3. Enter your **API Key**.
4. Enter the **Transcription AI Model Name** (default: `gpt-4o-mini-transcribe`) and **Protocol AI Model Name** (default: `gpt-4o-mini`).
5. (Optional) Customize the **Transcription** or **Protocol** system prompts.
6. Tap **Save Settings**.

---

## 🔐 Data & Privacy

- Recordings, transcripts and protocols stay on the device in app-private storage.
- Android cloud backup is disabled (`android:allowBackup="false"`). Explicit Android 12+ device-transfer and cloud-backup rules, plus legacy backup rules, exclude every app storage domain, including recordings, the meeting database, and the settings DataStore containing the API key.
- The API key is stored in app-private storage, unencrypted, and is only sent to the endpoint you configure.
- A deletion that cannot remove the audio file keeps the meeting record, so a file is never left behind without a reference.

---

## 🤖 AI Disclosure

Parts of this project were developed with AI coding assistance. The maintenance work from issues
#20–#30 (November 2026) was done with **Ikaros**, a Hermes Agent instance by Nous Research, under
review of the repository owner: every change went through a pull request that was reviewed and
merged by hand, and architecture and product decisions stayed with the human maintainer.

---

## 📜 License

This project is licensed under the GNU Affero General Public License v3.0 - see the [LICENSE](LICENSE) file for details.
