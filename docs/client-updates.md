# Android distribution and automatic updates

The client checks the published version in
`https://raw.githubusercontent.com/sickmitch/compass/refs/heads/main/dist/last.txt`.
The file contains one APK filename, for example `compass-0.28.46.apk`.
A newer version prompts for consent; the accepted download comes from
`https://github.com/sickmitch/compass/raw/refs/heads/main/dist/<filename>`.

Foreground checks are throttled; prompts are deferred during navigation/Picture in
Picture. Downloading uses a separate HTTP client without backend credentials, bounded
size/timeouts and private temporary storage. Package identity, increasing version and
signing certificate are checked before handing the APK to Android's installer.
Installation requires Android's per-app unknown-source permission and user confirmation;
it is not silent. Interrupted downloads can be retried after reopening the app.

## Publishing

1. Increase Android versionName/versionCode for a new release.
2. Build with the existing signing identity. Preserve the debug keystore when moving
   build machines if distributing the debug application ID; a different certificate
   cannot update an installed application in place.
3. Successful assembly copies a suffix-free versioned APK into `dist` and updates
   `dist/last.txt`. Do not advertise a version that has not been built successfully.
4. Publish the APK and pointer together to the main branch; if publication is separate,
   make the APK available before updating the pointer.

## Device acceptance

With an older compatible signed version installed, verify that a newer published APK
prompts once, refusal preserves the current app, acceptance downloads and opens the
Android installer, and saved profiles remain after installation. Verify navigation does
not get interrupted by the prompt. Return version, observed steps and any error message;
never return passwords or signing material. Build success alone does not validate this
end-to-end flow. Historical release evidence remains in Git and the historical gates.
