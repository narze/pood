# Pood Android

The app is a floating bubble that records speech and streams it to the Pood gateway; the transcript goes to the clipboard.

## Build

The toolchain (Java 21, Android cmdline-tools, Gradle) is pinned in `mise.toml`:

```sh
mise install
eval "$(mise env)"
sdkmanager --install "platforms;android-37.0" "build-tools;37.0.0" "platform-tools"
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug                        # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Setup on the phone

1. Install the Tailscale app, sign in to the same tailnet as the gateway, and connect.
2. In Pood, enter the gateway URL, for example `https://<server>.<tailnet>.ts.net:8790`, then tap **Save & test**. It
   should say "Connected as you@github".
3. Grant three permissions: Microphone, Display over other apps, and Notifications.
4. Turn on **Show bubble**.

## Usage

1. Tap the bubble to open the small window.
2. Tap the record button, speak, and tap it again to finish. Partial text appears live while you speak.
3. When the final text arrives, it's copied to the clipboard if "Copy transcript automatically" is on. You can also
   tap **Copy**.
4. Paste it anywhere.
5. Tap **Hide** to collapse the window back to the bubble.

The window also shows the 3 most recent transcripts; tap one to copy it again. The full history is on the main screen.

The persistent notification has two actions:
- **Record / stop**: records without opening the bubble. Useful in apps that hide overlays.
- **Quit**: turns the bubble off.

**Why the bubble must be turned on from the app.** Android 14 and later only let an app use the microphone from a
foreground service if that service was started while the app was on screen. Pood therefore stops when you quit it, and
it is not restarted automatically.
