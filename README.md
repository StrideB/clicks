# Clicks

Clicks is a native Android launcher experiment: a home screen first, then a persistent custom IME keyboard and accessibility-assisted split-screen workflow.

## Architecture

Android does not expose a public API for resizing arbitrary third-party apps to a custom height. Clicks will use supported system surfaces:

- Launcher activity: owns the home screen and app ribbon.
- Custom IME: keeps the keyboard docked system-wide when enabled.
- Accessibility service: requests supported global actions such as split-screen toggles, with OEM-specific fallbacks.

That means the goal is a real, installable launcher and keyboard, not a fake in-app mockup and not a root-only window manager.

## Current State

This first scaffold is intentionally small:

- `com.fran.clicks`
- Default home launcher activity
- Installed-app list
- Tap an app to launch it
- Long-press an app to open its Android app settings

Build with:

```sh
env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```
