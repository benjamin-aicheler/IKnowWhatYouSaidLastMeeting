Create an Android app to record, transcribe and protocolize meetings.
Name the app "Last Meeting"

Steps (What a user can do):
- Record meeting (audio), 
- Automatically save audio as (suggest a format) with start and end date/time
- Optional set a title on details view
- Optional send audio to ai to create a transcript (save text result together with audio, so it stays available)
- Optional send audio to ai to create a protocol (save text result together with audio, so it stays available)
- Optional download audio as (suggest a format), transcript or protocol as pdf
- Optional share audio as (suggest a format), transcript or protocol as pdf (Sharesheet?)
- Optional delete audio, transcript and protocol

Configuration (What a user can setup):
- The user must configure an openai compatible api endpoint, including api key, if they want to transcribe or protocolize
- The user must configure a multimodal ai model that can process the audio and create a transcript or protocol, if they want to transcribe or protocolize
- The user can configure a system prompt for transcription and protocol

UI:
- Recorded meetings can be shown in a list or calendar view
- A meeting has a details view to: 
  a) set a title
  b) create a transcript
  c) create a protocol
  d) read the transcript
  e) read the protocol
  f) delete the meeting (and all associated files)
  g) delete the transcript
  h) delete the protocol

Considerations:
- Recording should not cancel if the screen turns off, or the screen should stay on
- Transcribing or protocolizing should not cancel if the screen turns off, or the screen should stay on