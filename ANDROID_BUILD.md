# HorizonMaps Android build

The Android app is separate from the website. The existing root `index.html` and Cloudflare Worker publishing setup are not changed.

## Build an APK without Linux or Android Studio

1. Open the repository's **Actions** tab.
2. Select **Build HorizonMaps Android APK**.
3. Select **Run workflow** and confirm.
4. Wait for the workflow to finish.
5. Open the completed workflow run and download the `HorizonMaps-debug-apk` artifact.
6. Extract the ZIP to get `app-debug.apk`, then install it on the Pixel.

The phone app opens the live site at https://maps.horizensearch.com, so website updates continue to appear without rebuilding the APK.

## Current Android Auto status

This commit adds the Android for Cars app service and a basic placeholder screen so the project has the starting structure. It does **not** yet show the live Leaflet map or provide turn-by-turn car-screen navigation. The car screen implementation, navigation state sharing, and Android Auto testing still need to be completed.

The current host validator allows all hosts for development only. It must be restricted before any release.
