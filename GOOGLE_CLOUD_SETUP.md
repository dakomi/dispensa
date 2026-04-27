# Google Cloud Console Setup for Dispensa Drive Sync

This guide walks through setting up a Google Cloud project for the **Dispensa** `play` flavor,
which uses Google Sign-In (Credential Manager) and Google Drive to sync pantry data.

It is written with two audiences in mind:

- **Fork maintainers** (e.g. `dakomi/dispensa`) — setting up their own project for development
  and testing.
- **Upstream maintainers** (e.g. `enricofrigo/dispensa`) — reviewing the approach before
  merging, or setting up their own project for production releases.

Each fork / release keystore combination needs its own OAuth credentials; the steps below
are the same for both.

---

## Overview

The sign-in flow requires:

1. A **Google Cloud project** with the Google Drive API enabled.
2. An **OAuth consent screen** (can remain in "Testing" mode during development).
3. A **Web OAuth 2.0 Client ID** — needed by Credential Manager as the `serverClientId`.
4. One or more **Android OAuth 2.0 Client IDs** — one per (package name, signing certificate)
   combination that will use the app.

> **Why a Web Client ID?**  
> The Android Credential Manager API uses the Web Client ID to issue an ID token that
> cryptographically identifies the user.  The Android Client IDs control which package
> name + SHA-1 combinations are permitted to obtain that token.

---

## Step 1 — Create a Google Cloud project

1. Open [Google Cloud Console](https://console.cloud.google.com/).
2. Click the project selector at the top → **New Project**.
3. Enter a name (e.g. `Dispensa Sync`) and click **Create**.
4. Make sure the new project is selected in the selector.

---

## Step 2 — Enable the Google Drive API

1. In the left sidebar go to **APIs & Services → Library**.
2. Search for **Google Drive API** and click on it.
3. Click **Enable**.

---

## Step 3 — Configure the OAuth consent screen

1. Go to **APIs & Services → OAuth consent screen**.
2. Choose **External** (unless you have a Google Workspace org), click **Create**.
3. Fill in the required fields:
   - **App name**: `Dispensa`
   - **User support email**: your email address
   - **Developer contact information**: your email address
4. Click **Save and Continue**.
5. On the **Scopes** page click **Add or Remove Scopes** and add:
   - `.../auth/drive.appdata` (for private sync file in App Data folder)
   - `.../auth/drive.file` (for household shared folder)
6. Click **Save and Continue** twice, then **Back to Dashboard**.
7. While in **Testing** mode you must add test users:
   - Click **Add Users** under the "Test users" section.
   - Add the Google account email(s) you will use for testing.

> **Publishing for production**: when you are ready to release, submit the app for
> OAuth verification.  Google will review the use of `drive.file` and `drive.appdata`.
> Until then the app works for listed test users only.

---

## Step 4 — Create the Web Client ID

This is the `serverClientId` used by the Android Credential Manager.

1. Go to **APIs & Services → Credentials**.
2. Click **+ Create Credentials → OAuth client ID**.
3. Application type: **Web application**.
4. Name: `Dispensa Web Client` (any descriptive name).
5. Leave Authorized redirect URIs empty (not needed for mobile-only flows).
6. Click **Create**.
7. Copy the **Client ID** value — you will need it in Step 6.

---

## Step 5 — Create Android Client ID(s)

One Android Client ID is required per (package name + signing certificate) combination.
You typically need at least two: one for debug builds and one for release builds.

### 5a — Get your signing certificate SHA-1

**Debug keystore** (same for all developers unless the keystore is customised):

```bash
# From the project root:
./gradlew signingReport
```

Find the `SHA1` value under `Variant: playDebug` (or `fdroidDebug`).

Alternatively, with `keytool`:

```bash
keytool -list -v -keystore ~/.android/debug.keystore \
    -alias androiddebugkey -storepass android -keypass android
```

**Release keystore**:

```bash
keytool -list -v -keystore <path_to_your_release_keystore> \
    -alias <your_key_alias> -storepass <store_password>
```

### 5b — Create the Android Client ID in Cloud Console

1. Go to **APIs & Services → Credentials → + Create Credentials → OAuth client ID**.
2. Application type: **Android**.
3. Fill in:
   - **Package name**: must match the variant exactly.
     - Debug build (with `applicationIdSuffix`): `eu.frigo.dispensa.debug`
     - Release build: `eu.frigo.dispensa`
   - **SHA-1 certificate fingerprint**: paste the SHA-1 from Step 5a.
4. Click **Create**.

Repeat for each (package name + SHA-1) combination you need (debug, release, CI, etc.).

> **Note**: Android Client IDs do not need to be referenced anywhere in the app's source code.
> Google Play Services automatically matches them by package name and signing certificate at
> runtime.  You only need to add them to the Cloud Console.

---

## Step 6 — Add the Web Client ID to the app

Open `app/src/play/res/values/config.xml` and replace the placeholder:

```xml
<string name="google_web_client_id" translatable="false">YOUR_WEB_CLIENT_ID</string>
```

with the **Client ID** you copied in Step 4, e.g.:

```xml
<string name="google_web_client_id" translatable="false">123456789-abc123.apps.googleusercontent.com</string>
```

> **Security**: the Web Client ID is not a secret — it is safe to commit to the repository.
> It identifies your Cloud project but cannot be used to access user data on its own.
> However, if you are a fork maintainer and do not want to commit your personal project's
> client ID, you can add `app/src/play/res/values/config.xml` to your `.gitignore` and
> supply it only locally or via CI environment variables.

---

## Step 7 — Build and test

```bash
# Build the play debug flavor
JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64 ./gradlew assemblePlayDebug

# Install on a connected device
adb install -r app/build/outputs/apk/play/debug/app-play-debug.apk
```

Open Settings → Sync → Sign in with Google.  You should see the Credential Manager account
picker, and after selecting an account you should see the Drive scope consent screen.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Sign-in sheet never appears | `google_web_client_id` is still the placeholder `YOUR_WEB_CLIENT_ID` | Complete Step 6 |
| `GetCredentialException` / no accounts | No Android Client ID registered for this package + SHA-1 | Complete Step 5 |
| Sign-in succeeds but Drive auth fails | Drive API not enabled, or Drive scopes not on consent screen | Steps 2 & 3 |
| "This app is not verified" warning | OAuth consent screen in Testing mode and account not listed as test user | Add test users in Step 3 |
| Works for you but not a colleague | Their debug keystore has a different SHA-1 | Add a second Android Client ID with their SHA-1 |

---

## Notes for upstream integration

If this fork is merged into `enricofrigo/dispensa`:

1. The upstream maintainer creates their own Google Cloud project following this guide.
2. They replace `YOUR_WEB_CLIENT_ID` in `config.xml` with their project's Web Client ID.
3. They register Android Client IDs for their debug and release signing certificates.
4. The `config.xml` file can be kept out of the upstream repository and supplied via CI,
   or committed with the real value — both approaches are valid.

The code itself has no hardcoded project IDs or credentials beyond the single
`google_web_client_id` string resource.
