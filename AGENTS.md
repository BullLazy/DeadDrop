# DeadDrop — Project Rules

## Project
Privacy-first Android file-transfer application.

## Stack
- Java
- Gradle
- Android
- Existing project infrastructure
- APK is the primary artifact

## Preserve infrastructure
- Do not change Gradle, AGP, SDK, Java, dependencies, or project structure
  unless required by the task.
- Do not introduce unnecessary libraries, frameworks, or databases.
- Preserve working architecture and build configuration.

## Privacy & security
- Never preview, open, execute, or interpret uploaded file contents.
- Preserve encryption and secure key handling.
- Remove supported file metadata/EXIF.
- Sanitize user-visible filenames.
- Use internal object IDs for stored files.
- Do not log file contents, encryption keys, or sensitive metadata.
- Minimize temporary and retained file traces.

## File transfer
- Keep upload/download processing chunked or streaming.
- Preserve resumability when modifying transfer logic.
- Do not load entire files into memory unnecessarily.

## Development
- Make the smallest coherent change.
- Inspect only files relevant to the task.
- Do not refactor unrelated code.
- Reuse existing tests and checks.
- Avoid unnecessary Gradle clean builds.
- Validate changes before reporting completion.
- Inspect the final diff.

## Git
- Never discard unrelated user changes.
- Do not rewrite history.
- Commit only after validation.
- Push only when explicitly requested.

## Infrastructure changes
If solving the task requires changing the existing project infrastructure,
STOP and ask the user before doing so.
