# Call audio: echo, feedback and volume – investigation, fix and test plan

## 1. What the call engine is

- **Engine:** WebRTC (`io.getstream:stream-webrtc-android` 1.3.10, stock libwebrtc Java API), one-to-one
  calls, Unified Plan, Firestore signalling (`calls/{id}` + `candidates`). File: `app/.../calls/CallManager.kt`.
- **Audio I/O:** WebRTC's `JavaAudioDeviceModule` (ADM): one `AudioRecord` (source `VOICE_COMMUNICATION`)
  and one `AudioTrack` (usage `USAGE_VOICE_COMMUNICATION`) for the whole process. Remote audio is mixed
  and played only by this ADM. The app never mixes, amplifies or re-plays audio. Its only sample
  processing is the voice-isolation step (section 3a) on its *own outgoing* signal, inside WebRTC's
  audio processing after echo cancellation, and it can only lower the level.
- **Routing/focus:** `calls/CallAudio.kt` (rewritten): `MODE_IN_COMMUNICATION`, audio focus, routes.

## 2. Root-cause investigation (code review against the 12 suspected causes)

| # | Suspected cause | Finding in the code |
|---|---|---|
| 1 | Remote audio played into the outgoing mic stream | **Not present.** No code touches samples; remote tracks are rendered only by the ADM. |
| 2 | Same remote track attached/played twice | **Not present** in normal flow (no app-side audio sinks). Now asserted: a second remote audio track or a second inbound audio RTP stream is logged as a warning and flagged in the call summary. |
| 3 | Duplicate renderers / sinks / peer connections / tracks | **Possible before the fix** through races: `startCall` ran on the caller's thread and could run twice on a double tap (two peer connections, the first never disposed, its microphone track still attached); duplicate `handleIncoming` deliveries (listener + push + notification tap) could interleave; the answer could be applied twice when the call document listener fired twice. **Fixed:** single-threaded state, single-flight set-up (`Mutex.tryLock`), in-flight de-duplication of incoming calls, answer applied exactly once. |
| 4 | Microphone captured by competing pipelines | Only through #3 (a leaked peer connection keeps its send stream). **Fixed** with #3; a second "microphone capture started" without a stop is logged as a warning. |
| 5 | Microphone picks up the device speaker and sends it back | This is what echo cancellation must remove; see #8. Also physically unavoidable when the **two test phones are in the same room**: each phone's microphone hears the other phone's speaker, which no echo canceller can know about. That loop grows louder over time (classic acoustic feedback). |
| 6 | Wrong output device / speakerphone state | **Partly.** Video calls forced the speaker even with a wired or Bluetooth headset connected; Bluetooth was never selected; device changes were ignored. **Fixed:** route policy (headset > Bluetooth > speaker for video / earpiece for voice), `AudioDeviceCallback`, modern `setCommunicationDevice` (API 31+) with legacy fallback. |
| 7 | Audio focus / communication mode / routing | Mode and focus were set, but the previous mode, speakerphone and mic-mute state were not restored, focus loss was not handled. **Fixed:** state saved and restored, focus listener re-applies mode/route after a phone call took focus. |
| 8 | AEC missing / disabled / ineffective | **Found a real defect introduced in the previous build.** It set `setUseHardwareAcousticEchoCanceler(false)`. In WebRTC this does not "add" its canceller on top of the phone's: `WebRtcAudioEffects.enable()` creates the platform `AcousticEchoCanceler` on the microphone session and **switches it off**, leaving only WebRTC's mobile software canceller (AECM), which is weak on loudspeaker (video calls use the loudspeaker). The original build used the phone's canceller only. **Fixed:** default is WebRTC's recommended mode (the phone's own canceller when the phone has one, otherwise WebRTC's); a user setting can force WebRTC's canceller for devices whose built-in canceller is poor. Exactly one canceller is active in every mode. |
| 9 | NS/AGC misconfigured or competing | **Found:** WebRTC's AGC (fixed-digital, +9 dB compression on Android) ran on top of the phone's voice-communication processing - a second gain stage that also raises residual echo and makes a loop louder. **Fixed:** WebRTC AGC off by default (user toggle in Diagnostics); NS uses the phone's suppressor when available (WebRTC turns its own off then). |
| 10 | Remote audio amplified repeatedly | **Not present.** No `setVolume`, `adjustStreamVolume` or system-volume change anywhere in the code (verified by search). |
| 11 | Reconnect adds tracks/callbacks | **Partly.** No tracks were re-added on reconnect, but callbacks of an old peer connection could still change the state of a new call (no session check). **Fixed:** every peer-connection callback carries a session number; stale ones are ignored. Tracks are now also disposed explicitly. |
| 12 | Voice and video differ | Same pipeline for both; video only adds a camera track. Camera on/off and camera switching never touch audio. |

**Conclusion.** No app-level path mixes received audio into the microphone. The code-level contributors
that were found and fixed are: the previous build switching the phone's echo canceller off (#8), a second
gain stage (#9), headset/Bluetooth routing (#6), and set-up races that could leave a second peer connection
with a live microphone track (#3/#4/#11). The remaining classic cause of a *growing* echo when testing is
physical: two phones close enough to hear each other. **None of this has been verified on two physical
phones yet** (only builds, unit tests and emulator tests ran) - see the test plan below.

## 3. Audio configuration now used

| Setting | Value |
|---|---|
| Capture | `MediaRecorder.AudioSource.VOICE_COMMUNICATION`, mono, after `MODE_IN_COMMUNICATION` is set |
| Playout | `AudioAttributes.USAGE_VOICE_COMMUNICATION` / `CONTENT_TYPE_SPEECH`, mono |
| Echo cancellation | Automatic: phone (vendor) AEC if `JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()`, else WebRTC AEC. Option: always WebRTC. Exactly one is active. |
| Noise suppression | Phone NS on Android 10+ when supported, otherwise WebRTC NS (never both) |
| Automatic gain control | WebRTC AGC **off** by default (Settings › Diagnostics › Automatic microphone level) |
| High-pass filter | on |
| Voice isolation | On (Settings › Diagnostics › Voice isolation to turn off): capture post-processing step after AEC/NS, see 3a |
| Bitrates | Automatic (3b): voice 12–40 kbps with network priority HIGH, video 180p–720p or paused |
| Volume | never changed by the app |
| Routes | Bluetooth / wired or USB headset / speaker (video) / earpiece (voice); user choice kept unless the device disappears; a newly connected headset takes over |

Changes to the echo/gain settings apply from the next call (the media engine is rebuilt only between calls).

### 3a. Voice isolation (noise between words)

`calls/VoiceGate.kt` (pure Kotlin, unit-tested on synthetic noise and speech) runs through
`calls/VoiceIsolationProcessor.kt` as WebRTC's *capture post-processing* (`ExternalAudioProcessingFactory`
of the WebRTC build in use): every 10 ms frame of the outgoing microphone signal, after the echo
canceller and the noise suppressor, before the encoder. The other person's audio never passes through it.

- Noise floor: minimum of the frame levels over 2 s (follows a room getting louder or quieter).
- Speech: level of any 2.5 ms block 9 dB above the floor (stays open while 5 dB above), never below -60 dBFS.
- Between words the level is lowered by 3 dB (quiet room, floor ≤ -72 dBFS) up to 15 dB (noisy, ≥ -48 dBFS).
- Never cuts speech: 10 ms lookahead (the gate opens before the first syllable is played out), 300 ms
  hold for word endings and short pauses, 1.5 ms attack / 150 ms release ramps (no clicks, no pumping).
- Never amplifies (gain ≤ 1), never mutes; adds 10 ms of delay. On the audio thread it never throws and
  turns itself off after any error, so the call continues unprocessed.

### 3b. Automatic call quality

`calls/CallQuality.kt` (`CallQualityPolicy`, unit-tested) picks the first quality from the network the
phone is on (Wi-Fi/Ethernet, mobile data, metered, the system's link estimate) and then checks every 2 s:
WebRTC's bandwidth estimate (`availableOutgoingBitrate`), the packet loss the other phone reports
(`remote-inbound-rtp.fractionLost`) and the round-trip time. Video steps 720p/540p/360p/180p/paused (the
camera keeps capturing 720p; only `scaleResolutionDownBy`, `maxFramerate`, `maxBitrateBps`, `active` of
the existing sender change, with `degradationPreference = BALANCED`); voice 40/32/20/12 kbps with
`networkPriority = HIGH`. Down fast (two bad checks, or one severe), up one step after three good checks
and 10 s without a downgrade; video pauses only after three severe checks in a row; mobile/metered data
never sends 720p. Nothing is renegotiated, so changes never interrupt the call. When the other side's
video stops arriving (no new decoded frame for two checks) the call screen shows their photo instead of
a frozen picture.

## 4. Invariants enforced in code

- One audio device module per process; at most one peer connection, one microphone track, one camera track.
- All call state changes run on one dedicated thread; set-up (call/accept) is single-flight.
- Every suspension point re-checks the session before touching a peer connection (a hang-up during set-up
  can't reuse a disposed connection); SDP and signalling steps have timeouts so a lost callback can't block calls.
- Teardown disposes the peer connection, our track handles, sources, capturer and texture helper, stops the
  stats job, restores mode/route/focus/mic-mute and increments the session so late callbacks are ignored.
- Mute toggles only the call's microphone track; camera toggles only the video track.
- Voice isolation shapes only the outgoing signal, after echo cancellation, with gain ≤ 1; quality
  changes only touch encoder limits of the existing senders.

## 5. Diagnostics (no audio, content, tokens or keys are recorded)

- In-memory event log (`calls/CallDiagnostics.kt`): call ID prefix, lifecycle states, ICE states,
  peer-connection/track counts, microphone/playout start/stop and errors (from the ADM callbacks),
  route and focus changes, applied audio processing, reconnect count, duplicate warnings, periodic stats.
- Logcat tag `PulseCallDiag` only in debug builds or when *Settings › Diagnostics › Show call details* is on.
- WebRTC statistics every 2 s: round-trip time, jitter, packet loss, concealment, audio levels, echo return
  loss / enhancement (when the active canceller reports them), number of inbound/outbound audio streams.
- *Show call details* also overlays these numbers on the call screen. The last call's summary is shown in
  Settings › Diagnostics (and included in "Copy report").

## 6. Two-device test plan (not yet executed - requires two physical phones)

**Setup:** two physical Android phones (ideally different makers / Android versions), the APK from CI,
two accounts. Put the phones **in different rooms** (or use headphones on at least one side) unless the
scenario says otherwise. On both phones enable *Settings › Diagnostics › Show call details*.
For every scenario, the listener must hear each word once, at a stable volume, without growing echo; the
details overlay must show `Audio streams: 1 in · 1 out`; record RTT/loss and the ERLE value if shown.

| # | Scenario | Steps | Pass criteria |
|---|---|---|---|
| 1 | Voice, both earpieces | A calls B (voice), both hold phones to the ear, talk alternately 60 s | No echo either side; volume stable |
| 2 | Voice, one speaker | B taps Speaker; talk 60 s | A hears no echo of A's own voice; B hears A once |
| 3 | Voice, both speakers | Both on speaker, phones in different rooms | No growing echo; if echo: switch *Echo cancellation* to the other option on the phone whose user's voice comes back, redo |
| 4 | Video, both cameras | A video-calls B (speaker by default) | As 3 |
| 5 | Video, camera off/on | B turns camera off 20 s, then on | Audio level and echo unchanged; overlay still 1 in / 1 out |
| 6 | Speaker → earpiece | During a call toggle Speaker off/on 3 times | Route changes each time; no echo burst; no second audio stream |
| 7 | Headsets | Plug/unplug a wired headset; connect/disconnect a Bluetooth headset during the call | Audio moves to the headset and back; speaker is not forced while a headset is connected |
| 8 | Mute | Each side mutes 10 s, then unmutes | The other side hears nothing while muted; no volume jump after unmute |
| 9 | Network interruption | Turn Wi-Fi off on A for ~5 s (mobile data on), then back | Call shows Reconnecting and recovers within 20 s; overlay still 1 in / 1 out; no echo |
| 10 | Background | A goes to the home screen for 30 s, returns | Audio continues; no duplicate audio |
| 11 | Back-to-back calls | End, immediately call again (5 times) | Every call: 1 in / 1 out; Diagnostics › Last call shows 0 warnings |
| 12 | Repeated calls | 10 calls of 1 minute | No accumulation of echo, volume or warnings |
| 13 | Device matrix | Repeat 1-4 on another maker / Android version | Same results; note which echo option works best per device |
| 14 | Same-room control | Phones side by side on speaker | Expected to howl/echo (physical coupling) - confirms test setup matters |
| 15 | Background noise | A calls B next to a running fan/TV; A talks, pauses 3 s, talks | B hears the noise clearly lower in A's pauses, and the first syllable after each pause complete; turn *Voice isolation* off on A to compare |
| 16 | Weak network | Video call; A walks to a weak Wi-Fi spot or switches to mobile data | The call continues; A's video gets softer or pauses (B then sees A's photo) and comes back; voice stays clear. Diagnostics › Last call shows the quality changes |

After each failing scenario, copy the Diagnostics report on both phones (it contains no personal data) and
note the *Echo cancellation* option used.
