# Time tracking radio widget

A home-screen widget for Android. It shows a grid of projects and two activities, `work` and `meeting`. Tapping a cell switches the recorded time to that project and activity. Tapping the selected cell stops recording. The state is kept by an external HTTP endpoint that you run yourself, so the app itself contains no endpoint, key or project list.

## What the widget shows

- A header row (blank, `work`, `meeting`) and one row per configured project, in the configured order.
- A ring is an unselected cell. A ring with a filled inner disc is the selected cell. A dashed ring marks the cell tapped last while its request is waiting or being sent. When several taps wait, only the last one is marked. A mark older than 10 minutes is removed at the next widget update or tap, even if its request never finishes, so it can stay visible until then, up to 30 minutes.
- One status line below the grid shows the current state in UTC, for example `Recording project-a / work since 08:39 UTC`.
- The widget refreshes on its own every 30 minutes at the minimum, after you save the settings, and after every tap.

## Endpoint contract

The app calls exactly the URL you configure. Any key must be part of that URL. All responses are HTTP 200 with a JSON body.

| Method | Body | Behaviour |
|---|---|---|
| GET | none | Returns the current selection. |
| POST | `{"project": "project-a", "activity": "meeting"}` | Sets the selection. |
| POST | `{"project": null, "activity": null}` | Clears the selection. |

A successful response:

```json
{"result": "ok", "selection": {"project": "project-a", "activity": "work", "since": "2026-11-07T08:39:00Z"}}
```

- `selection` is `null` when nothing is selected.
- A failure is `{"result": "ng", "message": "..."}`. The message is shown under the grid. When the response includes `selection`, the app shows that selection; when it is absent (for example a wrong key), the app keeps the previously shown selection.
- `activity` is `work` or `meeting`. `since` is ISO-8601 in UTC with a trailing `Z`.
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

## Install

1. Open the [Releases page](https://github.com/HiromiShikata/time-tracking-radio-widget/releases) on the device and download the APK of the latest release.
2. Allow installing apps from this source when Android asks.
3. Open the app from the launcher, enter the endpoint URL and the projects (one per line), and tap Save.
4. The settings screen checks the connection and shows the outcome under the Save button. It stays open; leave it with Back.
5. Add the widget to the home screen. The settings screen opens as its configuration screen.

## Settings

The endpoint URL must start with `https://`. Project names must be non-empty and unique. The settings and the event log are stored only in the app's private storage. Backups and device transfers exclude them.

## Updates

Every 6 hours the app checks the latest GitHub release. When a newer version exists it posts a notification. Tapping the notification downloads the APK and starts the Android package installer. Releases are signed with the same key, so an update installs over the previous version.

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
