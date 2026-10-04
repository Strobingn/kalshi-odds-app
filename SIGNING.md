# Release signing

Published DipHunter APKs are signed with a release keystore kept in GitHub Actions secrets. The keystore is not committed.

## Secrets

| Secret | Meaning |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Base64 of the `.jks` file, one line, no wrapper |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore store password |
| `RELEASE_KEY_ALIAS` | Key alias inside the keystore |
| `RELEASE_KEY_PASSWORD` | Key password |

CI decodes the keystore into a temporary file and passes the path and passwords to Gradle through environment variables. The workflow does not print those values. A push to `grokbot` fails when any of those secrets is missing or empty, and it does not publish a debug-signed APK. Pull-request builds, including forks, may compile with the runner debug keystore and never publish a release.

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

Older installs were signed with `app/signing/diphunter-debug.jks`. That keystore and its passwords are in public git history (`271856d`, `a558f6c`) and are treated as a public certificate. Nothing in the tree or the workflow uses that file. v1.2+ is signed with the private release key. Android refuses an update over the old certificate, so those installs must uninstall once, then install the new APK. Uninstall clears app data, including the Kalshi API key — export a keys backup from Settings first if you need it.

After that, updates signed with the same certificate install in place. The in-app updater compares the downloaded APK with the certificate of the app that is already installed, not with a fingerprint baked into the source.
