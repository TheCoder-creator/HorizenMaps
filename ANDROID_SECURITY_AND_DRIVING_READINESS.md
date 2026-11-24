# HorizonMaps Android security and driving-readiness review

## Release status

**Not approved for real-world navigation use.** A successful Gradle build only proves that the source compiles. It does not prove that route guidance is correct, that Android Auto accepts the app for distribution, or that the app works correctly in a specific vehicle.

The Android Auto screen currently renders OpenStreetMap tiles and requests location updates. It does not yet calculate a route on the car screen, draw a route line, provide turn-by-turn navigation, reroute, or provide verified arrival estimates. The centered blue marker is a map-center marker, not a reliable heading-aware vehicle indicator.

Do not use this build as a driving navigation system or as a replacement for the vehicle's built-in navigation.

## Security controls currently present

- The phone WebView loads only `https://maps.horizensearch.com` as a top-level page.
- The trusted-origin check rejects other hosts, non-HTTPS schemes, unexpected ports, and credential-bearing URLs.
- JavaScript bridges are not exposed to page content.
- File and content access are disabled in WebView.
- File-URL cross-origin access is disabled.
- Mixed content is blocked.
- TLS certificate errors are canceled; the app never asks the user to ignore them.
- Safe Browsing is enabled.
- Third-party cookies and all WebView cookies are disabled.
- WebView debugging is explicitly disabled.
- WebView camera/microphone permission requests are denied.
- WebView popup windows are disabled.
- WebView process crashes show a static error instead of automatically reloading the page.
- WebView resources are cleaned up with the Activity lifecycle.
- Android backup is disabled and cleartext traffic is disabled.
- The GitHub Actions workflow has read-only repository permissions, a job timeout, and a short-lived build artifact.
- Unit tests cover trusted-origin URL validation.

## Remaining security review items

1. Review the complete website and Cloudflare Worker configuration, including response headers and every third-party script. The website CSP currently allows inline scripts and styles; this is a tradeoff that needs a separate web security review.
2. Review the AndroidX Car App host allowlist against the actual Android Auto host and supported test hosts before release. The current code uses the library's sample allowlist; do not replace it with an allow-all validator.
3. Review dependency versions and update policy. The build uses pinned library versions, but transitive dependencies still need vulnerability scanning.
4. Configure a production signing key outside the repository. Never commit a keystore or passwords to Git.
5. Keep debug APKs for development only. A successful debug build is not a production release.
6. Have an independent adult review the code and build provenance before installing anything.

## Navigation work still required

- Destination search and navigation-intent support.
- OSRM route calculation with clear route failure handling.
- Route-line drawing and a heading-aware vehicle marker.
- Turn-by-turn steps, remaining distance, and arrival estimates.
- Off-route detection and safe rerouting behavior.
- Voice guidance with Android audio focus and sensible announcement timing.
- Correct handling of missing, stale, inaccurate, or denied location.
- Clear warnings when map tiles, geocoding, or routing services are unavailable.
- Network loss and offline behavior; the current app does not provide offline maps or offline routing.
- Day/night styling and testing on different Android Auto screen sizes.
- Compliance review against Google's current navigation-app quality checklist.

## Required test gates before any driving use

1. Run unit tests and Android lint in CI.
2. Test using Android Auto Desktop Head Unit (DHU) with simulated routes and location.
3. Test route parsing, maneuver progression, rerouting, GPS loss, stale GPS, and network loss using repeatable test cases.
4. Test parked in each target vehicle: Mercedes EQS and Toyota hybrid. Confirm the Android Auto host version, display layout, touch controls, voice audio, and phone connection behavior.
5. Have an adult supervise all vehicle testing. Keep the vehicle parked while checking menus and destination entry.
6. Compare every route and instruction with the vehicle's built-in navigation. Do not rely on HorizonMaps for a trip until it has been independently validated.
7. Complete Google's navigation app quality checklist and distribution requirements.

## Data and service notes

The app requests device location to show the map. Map tiles, geocoding, and route data are provided by external OpenStreetMap-related services. Their availability, accuracy, coverage, and usage policies are outside this app's control. Speed-limit tags and map data can be missing or wrong. The app must never present missing data as confirmed information.
