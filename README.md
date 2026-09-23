# Last Meeting 🎙️

**Last Meeting** is a modern Android app built with **Jetpack Compose** and **Material 3** to record meeting audio, automatically transcribe it, and generate structured protocol summaries using any OpenAI-compatible multimodal API endpoint (`/v1/chat/completions`).

---

## ✨ Features

- 🎙️ **Persistent Audio Recording**:
  - Records audio in high-quality `M4A` (AAC) format.
  - Powered by an Android **Foreground Service** with persistent notification and microphone service type—recording continues uninterrupted when the screen turns off or the app is minimized.

- 🤖 **Multimodal AI Transcription & Protocolization**:
  - Leverages standard OpenAI Multimodal Chat Completions (`/v1/chat/completions`) by sending base64-encoded audio.
  - Compatible with **OpenAI**, **OpenRouter**, **Gemini**, **Groq**, or any OpenAI-compatible custom API endpoint.
  - Separate system prompts for verbatim transcription and executive protocol creation.

- 📅 **List & Calendar Views**:
  - Toggle seamlessly between a sorted **List View** and an organized **Calendar View** on the main screen.

- 📝 **Meeting Details & Management**:
  - Custom meeting titles (defaults to timestamp e.g. "Meeting 2024-11-24 14:30").
  - In-app audio player to listen to recordings.
  - Read transcript and protocol text directly in the app.

- 📄 **Export & Share**:
  - Export transcript or protocol as a cleanly formatted **PDF** directly to the public `Downloads/LastMeeting` folder.
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
- **Preferences**: Jetpack DataStore Preferences (secure local API key and prompt settings)
- **HTTP Client**: OkHttp / Ktor with `kotlinx.serialization`
- **PDF Generation**: Native `android.graphics.pdf.PdfDocument` & `MediaStore`

---

## 🚀 Getting Started

### Prerequisites

- **Android Studio**: Ladybug / 2024.2.1 or newer
- **JDK**: Java 17 or Java 21
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

---

## ⚙️ Configuration & Settings

To use AI transcription or protocolization:

1. Open the app and tap the **Settings** icon (⚙️) in the top right.
2. Enter your **OpenAI Compatible Base URL** (default: `https://api.openai.com/v1/`).
3. Enter your **API Key**.
4. Enter the **Multimodal Model Name** (e.g. `gpt-4o-audio-preview`, `gpt-4o`, `google/gemini-flash-1.5`, etc.).
5. (Optional) Customize the **Transcription** or **Protocol** system prompts.
6. Tap **Save Settings**.

---

## 📜 License

This project is licensed under the GNU Affero General Public License v3.0 - see the [LICENSE](LICENSE) file for details.
