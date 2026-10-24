# android-sample — TradingView chart in a WebView + live prices over OkHttp

A self-contained Android app: TradingView's free **advanced chart** widget inside a
`WebView`, plus a live price badge fed by TradingView's websocket using OkHttp.

- `compileSdk 34`, `targetSdk 34`, `minSdk 21`, Java 8 source level
- App libraries: **`com.squareup.okhttp3:okhttp:4.12.0`** from Maven Central (Apache-2.0).
  No AndroidX, no paid SDK, no API key. (`google()` is still needed for the Android Gradle
  Plugin itself; `jcenter()` is gone for good — JFrog's final shutdown was 2 Feb 2026.)
- OkHttp **5.x is avoided on purpose**: `okhttp-android:5.5.0` declares `minCompileSdk 37`,
  which conflicts with `compileSdk 34`.

## Layout

| File | What it does |
| --- | --- |
| `app/src/main/assets/chart.html` | Loads the TradingView widget; reads `?symbol=` / `?interval=` from the WebView URL; exposes `window.setQuote()` |
| `app/src/main/java/.../MainActivity.java` | WebView configuration, symbol switching, UI updates |
| `app/src/main/java/.../TvQuoteClient.java` | OkHttp websocket: auth, quote session, heartbeat echo, reconnect |
| `app/src/main/java/.../TvProtocol.java` | `~m~` framing, packet building, `qsd` parsing (pure Java, no Android imports) |
| `app/src/main/java/.../TvJson.java` | Tiny dependency-free JSON reader/writer so the protocol layer is JVM-testable |
| `app/src/test/java/.../TvProtocolTest.java` | JVM unit tests pinning the framing to this repository's reference implementation |

The protocol mirrors `src/protocol/framing.ts`, `src/client/client.ts` and
`src/quote/quote-session.ts` of this repository: anonymous access authenticates with
`set_auth_token` + `unauthorized_user_token`, and heartbeats (`~h~<id>`) must be echoed.

## Build it without a laptop

**GitHub Actions (easiest, free):** `.github/workflows/android-apk.yml` builds
`app-debug.apk`; download it from the run's artifacts.

**Termux (aarch64 phone):**

```bash
pkg update && pkg install -y git openjdk-17 gradle aapt2
git clone https://github.com/<you>/TradingView-API.git && cd TradingView-API/android-sample
mkdir -p ~/.gradle
# Google ships an x86_64-only aapt2; use Termux's aarch64 build instead (supports SDK <= 34).
echo "android.aapt2FromMavenOverride=$PREFIX/bin/aapt2" >> ~/.gradle/gradle.properties
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
```

**Desktop:** open `android-sample/` in Android Studio Koala (2024.1.1) or newer.
Arctic Fox (2020.3.1) tops out at AGP 7.0 and cannot compile `compileSdk 34`
(that needs AGP 8.1.1+, i.e. Hedgehog 2023.1.1 or newer).

## Tests

```bash
./gradlew test      # runs TvProtocolTest on the JVM, no emulator needed
```
