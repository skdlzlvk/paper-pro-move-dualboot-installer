# Windows installer status

The production installer does not exist yet.

`Invoke-PaperMovePreflight.ps1` is deliberately read-only. It checks Windows,
required command availability, and optional network reachability. It does not:

- authenticate to the device;
- upload a file;
- modify a boot flag;
- mount or write a partition;
- install Android; or
- change stock OS.

Run:

```powershell
.\Invoke-PaperMovePreflight.ps1
```

For machine-readable output:

```powershell
.\Invoke-PaperMovePreflight.ps1 -Json
```

The future installer must implement the controls in
[`docs/SAFETY.md`](../docs/SAFETY.md) before any write operation is exposed.
