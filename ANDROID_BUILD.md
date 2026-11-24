# HorizonMaps Android build

The Android app is separate from the website. The root `index.html` and Cloudflare Worker publishing setup are not changed by Android builds.

## CI build

1. Open the repository's **Actions** tab.
2. Select **Build HorizonMaps Android APK**.
3. Select **Run workflow** and confirm.
4. Wait for lint, unit tests, and APK build to finish successfully.
5. Open the completed workflow run and inspect its logs and commit.
6. The `HorizonMaps-debug-apk` artifact is a development build, not a production release.

Do not treat a successful build as proof that navigation is safe or ready. Do not install or use a debug build for real driving without an adult's review and the required parked testing.

The phone app opens the live site at https://maps.horizensearch.com. Website changes can therefore affect the phone WebView without an APK rebuild.

## Current Android Auto status

The Android Auto service currently draws OpenStreetMap tiles and requests location updates. It is an early map renderer only. It does not yet calculate routes on the car screen, draw a route line, provide turn-by-turn guidance, reroute, or provide validated arrival estimates.

The blue center marker is not yet a heading-aware vehicle marker. The current app is not ready for real-world navigation and must not replace the vehicle's built-in navigation.

## Security and readiness

See [ANDROID_SECURITY_AND_DRIVING_READINESS.md](ANDROID_SECURITY_AND_DRIVING_READINESS.md) for the security controls, remaining risks, and required test gates.

The Android Auto host validator currently uses the AndroidX sample allowlist. Keep host validation enabled; never switch to an allow-all validator for a release.

A production release also needs a protected signing process, dependency review, Google navigation-app quality review, Desktop Head Unit testing, and separate parked testing in each intended vehicle.
