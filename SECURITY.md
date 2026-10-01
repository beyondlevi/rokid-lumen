# Security policy

## Reporting a vulnerability

Please report security issues privately, through GitHub's private vulnerability reporting:
open the repository's **Security** tab and select **Report a vulnerability**
(<https://github.com/beyondlevi/rokid-lumen/security/advisories/new>). Don't open a public
issue for a vulnerability.

Include what you found, how to reproduce it (the glasses and phone models, the app versions),
and what an attacker could do with it. You will get an answer from the maintainer on the
report itself.

## Scope and known issues

Rokid Lumen runs with an accessibility service, shell privileges gained through ADB (the
self-arm), and a hotspot and proxy on the phone. The security model, and the issues already
known in this version (ADB over TCP left listening on port 5555, file-based IPC on external
storage, the proxy without per-session authentication, among others), are described in
[docs/security.md](docs/security.md). Reports that add to that list, or show a way past what it
describes, are welcome.

## Supported versions

Only the latest release gets fixes.
