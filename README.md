# Call Recorder

A personal, ad-free Android call recorder. No subscriptions, no ads, and no
"this call is being recorded" announcement — because it records through the
**microphone**, not Samsung's built-in call recorder.

## What it does
- Auto-starts recording when a call connects, stops when it ends.
- Saves small AAC/`.m4a` files locally (default 32 kbps mono ≈ 14 MB/hour).
- In-app list to play, share, and delete recordings.
- Checks this repo's GitHub Releases and installs updates in-app.

## Important limitation (read this)
On a non-rooted Android 10+ phone (including Samsung S26 Ultra), Google blocks
third-party apps from capturing the real call stream. This app records the
**microphone**, so:
- **Use speakerphone** for clear two-way audio.
- Without speaker, your side is clear and the other side is faint/absent.

There is no code workaround without rooting the device.

## How it builds
There is no local build step. **GitHub Actions** builds a signed APK on every
push to `main` and publishes it as a Release. The first build also generates a
stable signing key and commits it, so every later build can install as an
update over the previous one.

## Install on your phone
1. Open the repo's **Releases** page on the phone.
2. Download the newest `call-recorder-vN.apk`.
3. Allow "install unknown apps" when prompted, then install.
4. Open the app and grant microphone + phone permissions.

## Updating
When you (or Claude) push a change, Actions builds a new release. Open the app
and it offers to download and install the update — or tap **Check for update now**.

## Legal
Recording your own calls is permitted in one-party-consent regions (e.g. India).
You are responsible for complying with the laws that apply to you.
