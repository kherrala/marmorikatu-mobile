# Cross-platform customization

How the shared Kotlin Multiplatform code adapts to Android and iOS: the `expect`/`actual` seams, the pluggable voice engines, and the per-platform build and runtime configuration.

Almost everything — repositories, transport, view models, the Compose UI — lives in `commonMain` and is identical on both platforms. Only the handful of capabilities that must touch the OS (microphone, speakers, vibration, the network stack, app lifecycle, notifications, screen orientation) are declared `expect` in common code and implemented once per platform in the `androidMain` and `iosMain` source sets of `core` and `composeApp`.

See also: [Architecture](architecture.md), [Protocols](protocols.md), [Alarms](alarms.md).

## The expect/actual seams

Each seam is a small `expect` declaration in `commonMain` with an Android and an iOS `actual`. Koin builds them uniformly in `core/src/commonMain/kotlin/fi/marmorikatu/core/di/CoreModule.kt`; the platform picks the implementation at link time.

### Audio — `core/.../audio/Audio.kt`

Abstracts push-to-talk recording (`AudioRecorder`), sequential clip playback (`AudioPlayer`), and the mic permission gate (`MicPermission`). Recordings are always AAC in an mp4/m4a container (`audio/mp4`, 16 kHz mono) so the server transcriber gets the same bytes on both platforms.

- **Android** (`Audio.android.kt`): `MediaRecorder` (MIC → MPEG-4/AAC, 16 kHz, mono, 32 kbps) writing to a cache temp file; `AudioPlayer` stages each WAV clip to a temp file and plays it through `MediaPlayer`, with all queue mutation on the main thread. `MicPermission` (`MicPermission.android.kt`) checks `RECORD_AUDIO` and defers to a launcher the Activity registers.
- **iOS** (`Audio.ios.kt`): `AVAudioRecorder` with `kAudioFormatMPEG4AAC` at the same 16 kHz mono settings, using an `AVAudioSession` in `PlayAndRecord`/default-to-speaker; `AudioPlayer` feeds each clip to `AVAudioPlayer`. The player's delegate is held as a long-lived field because `AVAudioPlayer.delegate` is a weak ObjC property that the Kotlin/Native GC would otherwise collect mid-clip. `MicPermission` uses the audio session's `requestRecordPermission`.

### Speech — `core/.../speech/Speech.kt`

Abstracts voice I/O behind two interfaces, `SpeechToText` and `SpeechOutput`, so native and server engines are interchangeable (see [Voice engines](#voice-engines)). The server implementations (`ServerStt`, `ServerTts`) live in common code; only the native engines are `expect class PlatformStt` / `expect class PlatformTts`.

- **Android** (`Speech.android.kt`): `PlatformStt` wraps `SpeechRecognizer` with `fi-FI` free-form recognition and partial results, mapping recogniser error codes to Finnish hint strings; `PlatformTts` wraps `TextToSpeech` with a `fi-FI` voice, dispatching completion by utterance id through one long-lived progress listener.
- **iOS** (`Speech.ios.kt`): `PlatformStt` streams `AVAudioEngine` mic buffers into an `SFSpeechAudioBufferRecognitionRequest` on an `SFSpeechRecognizer(fi_FI)`, requesting Speech authorization at runtime; `PlatformTts` uses `AVSpeechSynthesizer` with the `fi-FI` system voice (its delegate is likewise a strong field to survive the weak ObjC reference).

### Haptics — `core/.../haptics/Haptics.kt`

Abstracts physical feedback for announcements: `alert()` (priority 0, unmistakable), `warn()` (priority 1), `tick()` (control confirmation). Critical alerts vibrate regardless of user preference.

- **Android** (`Haptics.android.kt`): the system `Vibrator` (via `VibratorManager` on API 31+). `alert()` is a two-pulse waveform with amplitude control when available, `warn()` a single pulse, `tick()` a predefined `EFFECT_TICK`.
- **iOS** (`Haptics.ios.kt`): the sanctioned feedback generators — `UINotificationFeedbackGenerator` (`.Error` for `alert()`, `.Warning` for `warn()`) and a light `UIImpactFeedbackGenerator` for `tick()`. iOS exposes no arbitrary-vibration API, so these match the system haptic language.

### HTTP engine — `core/.../transport/http/HttpClientFactory.kt`

Abstracts the Ktor engine behind `expect fun platformHttpClient(...)`; the shared `createHttpClient()` layers the same JSON, timeout, and logging config on top.

- **Android** (`HttpClientFactory.android.kt`): `HttpClient(OkHttp)`.
- **iOS** (`HttpClientFactory.ios.kt`): `HttpClient(Darwin)`.

### App foreground — `core/.../lifecycle/AppForeground.kt`

Abstracts a `StateFlow<Boolean>` of whether the app is on screen, used to decide when to hold or drop live connections.

- **Android** (`AppForeground.android.kt`): observes `ProcessLifecycleOwner` (`onStart`/`onStop`), registered on the main thread.
- **iOS** (`AppForeground.ios.kt`): observes `UIApplicationDidBecomeActive` / `UIApplicationWillResignActive` on the main operation queue.

### Background mode — `core/.../background/BackgroundMode.kt`

Abstracts keeping the announcement stream alive off-screen: `supported`, `ensurePermission()`, `setEnabled()`. This is where the two platforms genuinely diverge — see [Background announcements](#background-announcements-a-real-platform-difference).

- **Android** (`BackgroundMode.android.kt`): `supported = true`. `ensurePermission()` requests `POST_NOTIFICATIONS` on API 33+; `setEnabled()` starts/stops the foreground `AnnouncementService`.
- **iOS** (`BackgroundMode.ios.kt`): `supported = false`, `ensurePermission()` returns `false`, `setEnabled()` is a no-op, so the settings UI can present the switch honestly instead of pretending.

### Platform module — `core/.../di/CoreModule.kt`

The shared `coreModule` registers every seam above as a Koin `single`, plus both server- and native-named speech engines. Alongside it, `expect val platformName` supplies the string baked into the MQTT client id.

- **Android** (`CoreModule.android.kt`): `platformName = "android"`.
- **iOS** (`CoreModule.ios.kt`): `platformName = "ios"`.

### Orientation — `composeApp/.../platform/Orientation.kt`

Abstracts `@Composable expect fun LockLandscapeWhileVisible()`, used by the full-screen camera viewer so a 16:9 still fills the screen.

- **Android** (`Orientation.android.kt`): sets the Activity's `requestedOrientation` to sensor-landscape while composed and restores the previous value on dispose.
- **iOS** (`Orientation.ios.kt`): a no-op — rotation is driven by the device and the app's declared supported orientations; programmatic locking is intrusive and version-specific.

## Voice engines

Voice I/O is pluggable behind `SpeechToText` / `SpeechOutput`, with two families of implementation and an A/B toggle on the debug screen:

- **Native** — the phone's own engines: Android `SpeechRecognizer` + `TextToSpeech`, iOS `SFSpeechRecognizer` + `AVSpeechSynthesizer`. They answer instantly and work without the house server, but Finnish support is not guaranteed on every device or iOS version.
- **Server** — the shared house pipeline: `ServerStt` records an m4a clip and POSTs it to Whisper transcription on the bridge; `ServerTts` streams Piper WAV clips (the same `fi_FI` "house voice" as the kiosk) through the shared `AudioPlayer`. `isAvailable()` is always `true`, so this is the guaranteed-quality path.

**Default:** native. `AppConfig` ships `useNativeStt = true` and `useNativeTts = true`; the app prefers the phone's own engines.

**Native → server fallback** (in `ShellViewModel`):

- Selection is guarded by availability: `pickStt()` uses the native engine only if `useNativeStt` **and** `platformStt.isAvailable()`; otherwise it uses the server engine. TTS is chosen the same way (`useNativeTts && platformTts.isAvailable()`).
- If a native STT attempt *fails at runtime* (e.g. no Finnish model on the device), `onSttFailure` retries the utterance **once** through the server pipeline before giving up. A failure from the server engine itself is not retried.

This is what makes iOS usable for Finnish STT out of the box: when `SFSpeechRecognizer(fi_FI)` reports unavailable, the app transparently records and sends the clip to Whisper.

## Platform configuration

The backend has **no authentication by design**: every service speaks plain HTTP inside the home LAN, and away from home a UniFi VPN puts the phone on that same LAN. The LAN is the security boundary, so both platforms must be told to allow cleartext to the house — this is an intentional choice, not an oversight.

### Android

- **Cleartext** — `composeApp/src/androidMain/res/xml/network_security_config.xml` sets a blanket `cleartextTrafficPermitted="true"`, referenced from the manifest's `android:networkSecurityConfig`. A blanket allowance (rather than pinned domains) keeps the in-app server-host override working against whatever LAN address the user enters.
- **Foreground service** — `AnnouncementService` (`core/.../background/AnnouncementService.kt`) is a `dataSync` foreground service that keeps only the announcements stream open (deliberately not MQTT — live room temperatures nobody is watching aren't worth the radio) and turns each event into a notification. It routes taps to the right screen by announcement kind, buzzes priority-0 alerts unconditionally, and uses distinct quiet/alert notification channels. Declared in the manifest as a non-exported `<service>`.
- **Runtime permissions** (`composeApp/src/androidMain/AndroidManifest.xml`): `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `VIBRATE`, `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC`, plus `INTERNET` and `ACCESS_NETWORK_STATE`. A `<queries>` block declares the `RecognitionService` and `TTS_SERVICE` intents — without them (Android 11+ package visibility) the recognizer reports unavailable and `TextToSpeech` never initialises, silently.

### iOS

- **App Transport Security** (`iosApp/iosApp/Info.plist`): `NSAllowsLocalNetworking` permits plain HTTP to LAN addresses, and an `NSExceptionDomains` entry for the house domain sets `NSExceptionAllowsInsecureHTTPLoads` (with subdomains) so remote-over-VPN requests to that domain are allowed as well.
- **Usage descriptions** (Finnish, shown in the system prompts): `NSMicrophoneUsageDescription`, `NSSpeechRecognitionUsageDescription`, and `NSLocalNetworkUsageDescription`. The bundle development region is `fi`, and supported orientations are portrait plus both landscapes.
- **arm64-only** — the framework declares only `iosArm64` and `iosSimulatorArm64` (no `iosX64`), because the MQTTastic dependency ships no x86_64 slice.

### Background announcements: a real platform difference

Background delivery is the one capability the platforms cannot match:

- **Android** holds the announcements stream open in the `dataSync` foreground service and posts a notification per event.
- **iOS** suspends an app's sockets shortly after it leaves the screen. Reaching a backgrounded iPhone would require the house server to send an APNs push, which the backend does not do today. `BackgroundMode.supported` therefore reports `false` on iOS and the setting is disabled rather than shown as a switch that would quietly do nothing.

## Building for iOS: pin arm64

Because only the arm64 targets exist, a command-line build must pin the architecture — a generic simulator destination fails. From the README:

```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -sdk iphonesimulator -configuration Debug ARCHS=arm64 ONLY_ACTIVE_ARCH=YES build
```

A framework-only compile check without Xcode:

```bash
./gradlew :composeApp:compileKotlinIosSimulatorArm64
```
