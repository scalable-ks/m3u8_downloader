# Security Notes

This file summarizes dependency security checks and mitigation guidance.

## Summary (2026-01-12)

- okhttp 4.12.0 / okio 3.9.0: okio CVE-2023-3635 fixed in >= 3.4.0; current versions are above the fix.
- kotlinx-coroutines 1.8.1: no direct CVE advisory found in public databases; some scanners report false positives.
- react 19.2.0 / react-native 0.83.1: no direct advisories found for these RN packages; React Server Components CVEs are server-side and not used by RN.
- ffmpeg-kit: depends on the FFmpeg version bundled in the AAR; FFmpeg has ongoing CVEs and must be kept current.

## Mitigations

- Keep FFmpegKit AAR updated to the latest LTS tag and record build metadata in
  `RnAndroidHlsApp/android/app/libs/ffmpeg-kit-full.version`.
- Review FFmpeg security advisories periodically: https://ffmpeg.org/security.html
- Re-run dependency audits on each release (npm/Gradle).

## Sources Checked

- Kotlin coroutines false-positive discussion: https://github.com/Kotlin/kotlinx.coroutines/issues/3840
- CVE search (Kotlin): https://www.cve.org/CVERecord/SearchResults?query=kotlin
- React Native package advisories (template/debugger-frontend): https://security.snyk.io/package/npm/%40react-native-community%2Ftemplate/0.83.1
- React Server Components vulnerability note: https://react.dev/blog/2025/12/03/critical-security-vulnerability-in-react-server-components
- FFmpeg security advisories: https://ffmpeg.org/security.html
