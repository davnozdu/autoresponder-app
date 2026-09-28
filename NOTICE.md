# NOTICE / Attribution

**AutoResponder** (`com.davnozdu.autoresponder`) is licensed under the GNU General
Public License v3.0 with the Additional Terms under Section 7 stated in [LICENSE](./LICENSE).

## Incorporated work: messenger (VoIP) call-recording mechanism

The mechanism for recording messenger (VoIP) calls — the far-party capture via a
dynamic `AudioPolicy` loopback-render mix, the mic near-party capture, the
two-track mux, and the identification of the calling app from the active
playback configuration — is derived from **CallVault**:

- CallVault — Copyright (C) 2026-present The CallVault Authors — GPL-3.0 (with
  Section 7 Additional Terms). Source: https://github.com/madkongo/CallVault

CallVault is itself a modified fork of **ShizuCallRecorder**:

- ShizuCallRecorder — Copyright (C) kitsumed (Med) — GPL-3.0 (with Section 7
  Additional Terms). Source: https://github.com/kitsumed/ShizuCallRecorder

In accordance with the Section 7 terms inherited from those projects, this
software:

- is a MODIFIED, INDEPENDENT work — **not** endorsed by, affiliated with, or
  supported by CallVault, ShizuCallRecorder, or their authors;
- uses its own name (**AutoResponder**) and its own package id
  (`com.davnozdu.autoresponder`) — it does not use the names, trademarks, or
  logos of CallVault or ShizuCallRecorder for branding or publicity;
- discloses in its user interface (About screen) that its messenger-recording
  feature is derived from CallVault, with a link to that repository.

## Upstream credited by CallVault, relevant to the incorporated code

- **scrcpy** (Genymobile) — Apache License 2.0 — the `AudioRecord`
  constructor-bypass workaround (`Workarounds.createAudioRecord`, PR #5154) that
  the far-party sink falls back to on ROMs whose `AudioRecord` constructor
  dereferences a missing `Context`. Source: https://github.com/Genymobile/scrcpy

## Names and trademarks

"Android" is a trademark of Google LLC. All other product names, trademarks, and
logos are the property of their respective owners and are referenced here only
for attribution as required by the licenses above.
