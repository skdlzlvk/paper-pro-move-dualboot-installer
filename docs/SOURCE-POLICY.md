# Public source policy

The private research workspace is not a source repository. It contains device
backups, diagnostic dumps, old builds, test keys, and third-party packages.
Nothing is copied from that workspace by default.

## Allowed

- independently authored source code;
- minimal, non-secret configuration required to explain interfaces;
- documentation and reproducible tests;
- user-owned photos and video after privacy review; and
- hashes or package names needed to document compatibility.

## Prohibited

- stock OS files or extracted proprietary application code;
- vendor libraries, firmware, or unpublished headers;
- Android system images;
- GMS/GApps and third-party APKs;
- test/private signing keys;
- Wi-Fi configurations and account data;
- device backups and logs containing personal data; and
- binaries without a documented, redistributable source and license.

## Publication process

1. copy only an explicit allowlist into a clean staging repository;
2. scan file names, content, and Git history;
3. reject unexpected binary and archive types;
4. review third-party notices and build dependencies;
5. commit locally;
6. scan the complete commit;
7. push to the public repository; and
8. verify the public tree independently.
