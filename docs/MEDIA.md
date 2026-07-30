# Media provenance and privacy review

The files in `media/` were recorded by the project owner on the development
device.

| File | Purpose | Privacy review |
| --- | --- | --- |
| `paper-home.jpg` | Paper Home and installed-app launch surface | No email, SSID, serial, or account identifier visible |
| `kindle-gallery3.jpg` | Kindle UI with Gallery 3 color settling | Signed-out/store content only; no account identifier visible |
| `gallery3-demo.mp4` | 7.36-second transition from Paper Home to Kindle | Start/middle/end frames reviewed; no account identifier visible |
| `pen-latency-demo.mp4` | 19.14-second, 60 fps visual demonstration of Paper Home pen response | Twelve evenly spaced frames reviewed; no email, SSID, serial, or account identifier visible; public copy has no audio track |
| `pen-latency-preview.jpg` | Retained still preview from the pen-response recording | Extracted from the reviewed recording at 15.6 seconds; no email, SSID, serial, or account identifier visible |

The pen video is a real-device visual demonstration, not a laboratory latency
measurement. Camera frame rate, exposure, display scan-out, and viewing
conditions limit conclusions from the recording.

The README contains an inline GitHub player backed by this browser-compatible
attachment:

- [GitHub user attachment](https://github.com/user-attachments/assets/d9355ed8-65f1-42e2-a7ff-482a260d9601)

The inline copy is H.264 High Profile, 720x1280 at 60 fps, uses yuv420p,
is muted, and has SHA-256
`0A3F83A509DF7DDBFC150C6CC000221CEF79F0A59F0E1642AEC6BFDF2E30C0B5`.
An external byte-range request returned HTTP 206 for the complete
3,383,411-byte asset. Chromium loaded it with `readyState=4` and a 19.15-second
duration; playback advanced `currentTime` while remaining on the GitHub README.

A full-resolution SooCloud playback copy remains available as a fallback:

- [public playback page](https://files.soocloud.app/share/233305e5-e89d-4f5e-a1b9-08227f697aaa)
- [direct H.264 MP4 stream](https://files.soocloud.app/share-stream/233305e5-e89d-4f5e-a1b9-08227f697aaa)

The full-resolution copy is H.264 High Profile, 1080x1920 at 60 fps, uses the
browser-compatible yuv420p pixel format, includes AAC audio, and has SHA-256
`92F395F091225B2B7AF2DF81B8A0B5E0BB71E9A40A58BB0F2D23E0E930C66161`.
The public stream was checked from outside the host for HTTP byte-range
support.

The untouched camera original is also preserved on SooCloud. It uses HEVC,
which did not play in every web browser, includes its original audio track,
and has SHA-256
`903B77C2DB787F4CF9813132DA1AB4B0B7A8255E481915695A065F74FDBDC419`.
It is available as an
[original-file download](https://files.soocloud.app/share-download/2a565c0a-bb53-4d24-b813-6ed5b493aadc).
The muted repository copy remains available as a fallback and has SHA-256
`89BC89AC3B3E5826872761F821DB90744120C016398128B488937F64EF3E45B4`.

The presence of an application or trademark in a screenshot indicates
compatibility testing only. Third-party applications are not included in this
repository.
