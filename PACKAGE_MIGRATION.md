# Package name migration

- Main application namespace and applicationId: `dev.dash.mula`
- Gecko plugin namespace and applicationId: `dev.dash.mula.geckoplugin`
- Servo plugin namespace and applicationId: `dev.dash.mula.servoplugin`
- Kotlin source packages and source directory paths migrated accordingly.
- Gecko dynamic class names and plugin package lookup updated.
- Download broadcast action identifiers updated to the new main package prefix.
- ProGuard keep rules updated.
- GitHub Actions Android API 37 installation uses the API 37.0 canary package and both engine plugins declare API 37.0 explicitly.

The Gecko and Servo plugins intentionally retain distinct application IDs because they are installed/loaded as separate plugin APKs.
