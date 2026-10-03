# Release signing

Published DipHunter APKs are signed with a release keystore kept in GitHub Actions secrets. The keystore is not committed.

## Secrets

| Secret | Meaning |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Base64 of the `.jks` file, one line, no wrapper |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore store password |
| `RELEASE_KEY_ALIAS` | Key alias inside the keystore |
| `RELEASE_KEY_PASSWORD` | Key password |

CI decodes the keystore into a temporary file and passes the path and passwords to Gradle through environment variables. The workflow does not print those values. If any secret is missing (a fork, or a local build), the build still succeeds and signs with the standard Android debug keystore (`~/.android/debug.keystore`). CI logs a warning that the APK is not release-signed.

## Generate a keystore

```bash
keytool -genkeypair -v \
  -keystore diphunter-release.jks \
  -alias diphunter \
  -keyalg RSA -keysize 2048 -validity 10000
```

Encode it without line breaks:

```bash
base64 -w 0 diphunter-release.jks
```

Store that line as `RELEASE_KEYSTORE_BASE64`. Use the alias and passwords you chose for the other three secrets. Do not commit the `.jks`.

## One-time reinstall

Older installs were signed with `app/signing/diphunter-debug.jks`. That certificate is no longer used. Android refuses to install an APK signed with a different certificate over the existing app. Uninstall once, then install the new APK. Uninstall clears app data, including the Kalshi API key — export a keys backup from Settings first if you need it.

After that, updates signed with the same certificate install in place. The in-app updater compares the downloaded APK with the certificate of the app that is already installed, not with a fingerprint baked into the source.
