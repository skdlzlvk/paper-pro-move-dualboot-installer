# Media provenance and privacy review

The files in `media/` were recorded by the project owner on the development
device.

| File | Purpose | Privacy review |
| --- | --- | --- |
| `paper-home.jpg` | Paper Home and installed-app launch surface | No email, SSID, serial, or account identifier visible |
| `kindle-gallery3.jpg` | Kindle UI with Gallery 3 color settling | Signed-out/store content only; no account identifier visible |
| `gallery3-demo.mp4` | 7.36-second transition from Paper Home to Kindle | Start/middle/end frames reviewed; no account identifier visible |
| `pen-latency-demo.mp4` | 19.14-second, 60 fps visual demonstration of Paper Home pen response | Twelve evenly spaced frames reviewed; no email, SSID, serial, or account identifier visible; public copy has no audio track |
| `pen-latency-preview.jpg` | README preview linked to the original pen-response recording | Extracted from the reviewed recording at 15.6 seconds; no email, SSID, serial, or account identifier visible |

The pen video is a real-device visual demonstration, not a laboratory latency
measurement. Camera frame rate, exposure, display scan-out, and viewing
conditions limit conclusions from the recording.

The README preview links to the owner's SooCloud copy of the original
recording:

- [public playback page](https://files.soocloud.app/share/2a565c0a-bb53-4d24-b813-6ed5b493aadc)
- [direct MP4 stream](https://files.soocloud.app/share-stream/2a565c0a-bb53-4d24-b813-6ed5b493aadc)

That original is 1080x1920 at approximately 60 fps, is 19.14 seconds long,
includes its original audio track, and has SHA-256
`903B77C2DB787F4CF9813132DA1AB4B0B7A8255E481915695A065F74FDBDC419`.
The public stream was checked from outside the host for HTTP byte-range
support. The muted repository copy remains available as a fallback and has
SHA-256
`89BC89AC3B3E5826872761F821DB90744120C016398128B488937F64EF3E45B4`.

The presence of an application or trademark in a screenshot indicates
compatibility testing only. Third-party applications are not included in this
repository.
