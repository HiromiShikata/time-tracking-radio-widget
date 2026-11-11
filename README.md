# Time tracking radio widget

A home-screen widget for Android. It shows a grid of projects and two activities, `work` and `meeting`. Tapping a cell switches the recorded time to that project and activity. Tapping the selected cell stops recording. The state is kept by an external HTTP endpoint that you run yourself, so the app itself contains no endpoint, key or project list.

## What the widget shows

- A header row (blank, `work`, `meeting`) and one row per configured project, in the configured order.
- An outlined circle is an unselected cell, and a circle with a filled inner disc is the selected cell. When a cell is tapped, the widget shows the projected selection at once, before any network call: the tapped cell is filled and the previously selected cell is emptied. Tapping the selected cell shows nothing selected. The server's answer then replaces the projection, with its own selection (its `since` may differ, for example at a meeting start).
- A tap is pending from the moment it is accepted, whether the device is online or offline. The status text follows the earliest pending tap: while that tap waits for the network, its third line reads `Waiting for network since 08:39:03`; while the tap is unsent, it reads `Saving… tapped 08:39:03`, where the time is the tap time; once its request is being sent, it reads `Saving… sent 08:39:03`, where the time is when the request was sent.
- The request a new tap sends is decided when the tap is accepted, against the selection the widget would show after every pending tap has been applied in order: a tap on the cell that would be selected sends a clear, and any other tap sends a selection. A pending tap keeps counting for this while its work is pending, even after the 24-hour display bound, with no age limit. At the start of every widget or refresh run, the app reads WorkManager's state for the tap work once: an entry whose work has finished or been cancelled is removed; an entry whose work is missing from WorkManager is removed once it is more than ten minutes old; an entry is kept when the state cannot be read. Every attempt sends that same request body, with the original `tappedAt`, so an endpoint can recognise a retried tap as the same tap.
- The status text shows the head of the pending taps only. While an earlier tap is at the head, a later waiting tap does not change the status text. When the head completes, the next pending tap becomes the head and its state is shown.
- When the endpoint cannot be reached because of a network failure (a name lookup, a connection, a timeout, a TLS handshake, or a connection that ends while the request, a redirect or the response is being handled), the tap stays pending and is attempted again no later than 60 seconds after the failure. A new tap, a widget update, opening the settings screen, and the app starting or being updated each send the pending taps at once, without waiting for that delay. While the device is offline the retry waits for the network. Retries have no attempt limit. Pending taps are sent in the order tapped, each with its original `tappedAt`, and a tap that is still waiting does not hold back the taps after it.
- A permanent transport failure (a malformed endpoint URL, an unknown service, or a protocol error) is not retried. The request is sent once, the tap is removed, and the status line shows the unreachable state (`⚠ Could not connect`).
- A tap still waiting from version 1.0.4 when this version installs is sent the way 1.0.4 sent it. Its request is decided when it is sent, from the selection shown then, and its body has no `tappedAt`, so the endpoint records the tap at the time it receives the request. It is not shown on the widget before its response. It follows the same retry rules as any other tap.
- When the system stops a tap's work before it finishes, the tap stays pending until the next attempt starts or the work ends.
- A complete response from the endpoint is final: a success, an error response, or a body that is not a valid response is not retried, and the tap is removed. Reads (refreshes) are not retried.
- A tap stops being pending when it is sent (a successful or an error response from the endpoint), when its work ends without a retry, or when its settings are gone when the work runs; its queue entry is removed when that happens, and an entry whose WorkManager work has finished or been cancelled is removed at the start of the next widget or refresh run. The projected selection and the status text stop showing it at the next widget update or tap.
- Two display time limits hide a stale projection. A pending tap (its work is queued, waiting for the network or being sent) loses its projected selection and its status text 24 hours after the tap. This limit affects the display only: the tap is still sent, with the request it was accepted with and its original `tappedAt`, and it keeps retrying. A tap that was never queued as work loses its projected selection 10 minutes after the tap, so a stuck request cannot keep it visible forever.
- Below the grid, the status text has four lines, and every time in it is UTC. It can be hidden with the settings switch.
  - `Recording project-a / work`, or `Not recording`.
  - `since 08:39 UTC`, shown only while recording.
  - The state: `Saving… tapped 08:39:03` while a tap is unsent, then `Saving… sent 08:39:03` once its request is being sent; `Waiting for network since 08:39:03` while the oldest unsent tap waits for the network; `⚠ {server message}` after a rejection; `⚠ Could not connect (showing state as of 08:39 UTC)` when a refresh cannot connect and nothing is pending. Otherwise, after the last event, it shows `Saved 08:39:04 (1.2 s)` after a successful save (the time the response arrived and the round trip from sending to the response), or `Updated 08:39:04` from the last successful refresh, whichever happened last.
  - `Queue 0 · app 1.0.12`: the number of unanswered queued taps and the app version.
- The widget refreshes on its own every 30 minutes at the minimum, after you save the settings, and after every tap.

## Endpoint contract

The app calls exactly the URL you configure. Any key must be part of that URL. All responses are HTTP 200 with a JSON body.

| Method | Body | Behaviour |
|---|---|---|
| GET | none | Returns the current selection. |
| POST | `{"project": "project-a", "activity": "meeting", "tappedAt": "2026-11-09T02:19:03.112Z"}` | Sets the selection. |
| POST | `{"project": null, "activity": null, "tappedAt": "2026-11-09T02:19:03.112Z"}` | Clears the selection. |

A successful response:

```json
{"result": "ok", "selection": {"project": "project-a", "activity": "work", "since": "2026-11-07T08:39:00Z"}}
```

- `selection` is `null` when nothing is selected.
- A failure is `{"result": "ng", "message": "..."}`. The message is shown under the grid. When the response includes `selection`, the app shows that selection; when it is absent (for example a wrong key), the app keeps the previously shown selection.
- `activity` is `work` or `meeting`. `since` is ISO-8601 in UTC with a trailing `Z`.
- `tappedAt` is optional for the endpoint: the app sends it with every POST, except for a tap queued by version 1.0.4, which has none. An endpoint that ignores unknown keys keeps working without changes. It is the time the app accepted the tap, as ISO-8601 in UTC with a trailing `Z` and always three fractional digits (for example `2026-11-09T02:19:03.112Z`). A retried tap carries its original `tappedAt`.
- A POST is answered with a redirect. The app follows the redirect with GET to read the result.

### Diagnostics (optional endpoint contract)

The app keeps an event log on the device (newest first, at most 200 entries) and shows it on the settings screen, where **Copy log** copies the whole text. After every widget request (a tap or a refresh) and after every connection check, the app POSTs the entries it has not delivered yet to the same endpoint URL:

```json
{"diagnostics": [{"at": "2026-11-08T00:14:00Z", "event": "tap_received", "detail": "project=project-a, activity=work"}]}
```

- Each POST carries at most 200 entries, oldest first. The body has no `project` or `activity` key, so an endpoint must treat a body with `diagnostics` as a diagnostics upload and not as a selection.
- A response `{"result": "ok", ...}` marks the entries as delivered. Any other response, including an endpoint that does not support diagnostics, leaves the entries on the device for the next attempt.
- The POST is answered with a redirect and the app follows it with GET, the same as for selections.
- The configured key is stored on the device as part of the endpoint URL, as before. It is never written to the log or shown there: the `key` query value is replaced by `***` in every logged URL and detail, and only the host of any redirect target is logged.
- The log records more detail for debugging. `app_started` is recorded once per process start with the app version, the Android SDK level, and the device manufacturer and model. `tap_received` names the tap time, the request it resolves to, the number of queued taps, and the WorkManager request id. `work_started` is recorded when a queued tap begins to be sent, with its delay, the WorkManager run attempt and the network state. `request_sent` names the request kind (`selection`, `clear`, `refresh`, `diagnostics_upload` or `update_check`) and, for a selection, the project, activity and tap time it sends. `http_response` and `exception` carry the elapsed milliseconds since the request was sent, and an `exception` names its phase (`connecting`, `reading` or `after_response`). `response_parsed` names the selection the server returned and the total milliseconds since the request was sent. `widget_status` is recorded whenever the status text the widget shows changes.

## Install

1. Open the [Releases page](https://github.com/HiromiShikata/time-tracking-radio-widget/releases) on the device and download the APK of the latest release.
2. Allow installing apps from this source when Android asks.
3. Open the app from the launcher, enter the endpoint URL and the projects (one per line), and tap Save.
4. The settings screen checks the connection and shows the outcome under the Save button. It stays open; leave it with Back.
5. Add the widget to the home screen. The settings screen opens as its configuration screen.

## Settings

The endpoint URL must start with `https://`. Project names must be non-empty and unique. The settings screen also has the switch `Show status details on the widget`, on by default. It is saved as soon as it is toggled, without Save, and the widget is redrawn at once; when it is off, the status text is hidden. The settings and the event log are stored only in the app's private storage. Backups and device transfers exclude them.

## Updates

The app checks the latest GitHub release every 6 hours. Two more triggers start a check when the network is connected: a selection run that completes normally (a tap that was sent and answered, or a refresh that was read) when the last completed check is more than six hours old, and the settings screen opening, at most once a minute (the foreground check). A selection that is retried or fails does not trigger a check.

When a newer release has an APK asset, the app downloads the APK in the background first. The download is written to a partial file, its size is compared with the size of the release asset, and only a file with the matching size is kept. The notification is posted only after that. The install is handed to Android through a PackageInstaller session, so the owner only approves it: the Android install confirmation appears when the owner opens the app, or, if the app is in the background, as a notification to tap.

The app uses two permissions. Notifications: on Android 13 and later the settings screen asks for them when it opens; without them no notification is posted, and the settings screen shows a guide button that opens this app's notification setting. Install unknown apps: when it is off, the settings screen shows a guide and a button that opens this app's setting. Tapping the notification does not install anything while this permission is off; the app opens the setting instead.

A failed download caused by the network (a transport error) is retried within five minutes, while the network is connected, and posts no notification. Any other failed download (a response that is not 2xx, or a size mismatch) is retried by the next run and posts no notification.

Every check is recorded in the diagnostic log as `update_check`, with the decision, the latest version, the download result, whether notifications were permitted, and whether a notification was posted. Every install step is recorded as `update_install` with its outcome: `session_committed`, `session_failed`, `pending_user_action`, `installed`, `failed`, `blocked_unknown_sources` or `missing_apk`. Entries are uploaded with the next diagnostics upload. Each retry the app schedules is recorded as `retry_scheduled`, with its kind (`tap_queue`, `legacy_tap` or `update_download`), its attempt number and the delay in seconds until its next attempt.

The check decision is one of four values: `up_to_date`, `newer_available`, `newer_without_apk` and `check_failed`. A release whose version is newer but has no APK is recorded with `decision=newer_without_apk` and is not announced, so no notification is posted for it.

Releases are signed with the same key, so an update installs over the previous version.

## Release signing

Release APKs are signed with a keystore that lives outside the repository. The release workflow needs two repository secrets:

- `RELEASE_KEYSTORE_BASE64`: the keystore file encoded as base64 (for example `base64 -w0 release.jks`).
- `RELEASE_KEYSTORE_PASSWORD`: the password of the keystore, which is also used for the key. The key alias is `time-tracking-radio-widget`.

The workflow decodes the keystore into the runner's temporary directory and sets `RELEASE_KEYSTORE_FILE` and `RELEASE_KEYSTORE_PASSWORD` only for the build step. Pull request CI builds debug APKs and runs tests and lint, and it does not use these secrets. Locally, `assembleRelease` reads `RELEASE_KEYSTORE_FILE` (a path) and `RELEASE_KEYSTORE_PASSWORD` from the environment, and fails with an error that names any missing variable.

Android only installs an update over an app that was signed with the same key. Losing the release keystore means no future release can update an existing installation: users must uninstall and reinstall the app, and their widget settings are lost.

## Development

Requirements: JDK 17 and the Android SDK (platform 36).

```sh
./gradlew testDebugUnitTest   # unit tests; writes screenshots to app/build/outputs/roborazzi
./gradlew lint                # Android lint
./gradlew assembleDebug       # debug APK
# the first Gradle build sets core.hooksPath to .githooks, so every push runs lint and unit tests
```

CI runs only the unit tests that the change can affect: `scripts/affected-unit-test-args.sh BASE HEAD` prints the Gradle arguments, and `scripts/affected-unit-test-args_test.sh` tests that script. Lint and the debug build always run.

Domain logic is plain Kotlin under `domain/`, use cases under `usecase/`, and Android adapters under `adapter/`. Debug builds use Android's default debug signing. `assembleRelease` needs the release signing variables described below and fails without them.

Releases are published by `.github/workflows/release.yml` on every push to `main`. The version name is `1.0.{run number}` and the tag is `v{version name}`.
