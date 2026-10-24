# Architecture

InkReader is deliberately dependency-light. It uses Android framework APIs,
SQLite and the pinned Shizuku client libraries, without a networking stack.

## Reader

- `TxtImporter` streams the selected content URI into a normalized UTF-8 file.
- Encoding detection samples the beginning of the file and handles UTF-8
  samples that end in the middle of a multibyte character.
- Chapters and 48K-character chunks are recorded in SQLite as byte ranges.
- `ReaderActivity` loads only the current chunk and paginates it with Android
  `StaticLayout`.
- The phone and e-ink renderer share the same character ranges. Page drawing is
  clipped to the selected page's first and last lines so adjacent-page text
  cannot leak into the footer area.
- Progress, bookmarks and settings stay in the app's private data directory.

## NFC panel

1. A Shizuku user service starts a privileged helper process.
2. The helper enables the vendor Q-Poll path and opens an ISO-DEP connection.
3. D1 is used to validate the supported panel profile; D8 checks write access.
4. A rendered page is converted to the 552x768 two-bit panel layout.
5. The frame is split into 2 KB source blocks and compressed as LZO1X.
6. D3 writes the next frame while the current image remains visible.
7. A turn sends D4. If the SHA-256 matches the preloaded frame, D3 is skipped.
8. DE polling waits for the controller to become idle.

The app never writes replacement DB/DA driver data.

## Threading and lifecycle

- File import, search and page rendering run off the UI thread.
- The panel service is opened only while the reader is active.
- Leaving the reader stops the panel session and rejoins normal NFC discovery.
- The app does not run a network service or a persistent background service.
