# AGENTS.md

Guidance for AI agents (and humans) working in the **Trails** repository.

## Project overview

Trails is a device-tracking / "find my device" system. It lets users locate,
ring and share the location of their devices across an Android/iOS app and a
web app, backed by a self-hostable Ktor server.

The repository is a **Gradle multi-module Kotlin project** combined with a
separate SvelteKit web frontend. The Gradle modules are declared in
[settings.gradle.kts](settings.gradle.kts):

| Module         | Path                          | Description                                                                                            |
|----------------|-------------------------------|--------------------------------------------------------------------------------------------------------|
| `:server`      | [`server/`](server)           | Ktor backend (JVM). REST + WebSocket/SSE API, SQLite persistence, Koin DI, Authentikt auth.            |
| `:shared`      | [`shared/`](shared)           | Kotlin Multiplatform module with the shared API contract (DTOs / entities) between server and app.     |
| `:app:shared`  | [`app/shared/`](app/shared)   | Compose Multiplatform app (common UI + platform code for Android/iOS), Room DB, Koin DI.               |
| `:app:android` | [`app/android/`](app/android) | Android application entry point (activities, manifest, resources).                                     |
| `app/ios`      | [`app/ios/`](app/ios)         | iOS application entry point (Xcode project, SwiftUI shell).                                            |
| `web/`         | [`web/`](web)                 | SvelteKit web app (Svelte 5, TypeScript, Tailwind, shadcn-svelte, Mapbox GL). **Not** a Gradle module. |

Shared root package for all Kotlin code: `es.jvbabi.trails`.

### Server layout ([server/src/main/kotlin/es/jvbabi/trails](server/src/main/kotlin/es/jvbabi/trails))

- `Application.kt` / `Main.kt` — application bootstrap; `rootModule` installs all
  Ktor plugins in order and finishes with `installRouting()`.
- `api/` — Ktor plugin installation (`installContentNegotiation`,
  `installAuthentication`, `installStatusPages`, WebSocket, SSE, …).
- `auth/` — Authentikt integration and session/device-selection auth.
- `config/` — application configuration model.
- `data/` — repositories and external services (e.g. Nominatim reverse geocoding).
- `database/` — persistence entities (`Device`, `User`, `Share`, `ActiveShare`, …),
  `DatabaseManager`, and `mapper/` that maps DB entities to shared DTOs.
- `di/` — Koin module setup (`installKoin`).
- `routes/` — HTTP/WebSocket route handlers. See coding rules below.

### Shared API contract ([shared/src/commonMain/kotlin/es/jvbabi/trails](shared/src/commonMain/kotlin/es/jvbabi/trails))

`@Serializable` DTOs shared across server, app and (conceptually) the web
client. Organised under `api/v1/…` (entities, request/response bodies) and
`shared/dto/…`. Keep this module free of platform- or server-specific code.

### App layout ([app/shared/src/commonMain/kotlin/es/jvbabi/trails](app/shared/src/commonMain/kotlin/es/jvbabi/trails))

Clean-architecture-ish structure: `data/` (Room database, remote `TrailsApi`,
repository implementations), `domain/` (models, repository interfaces, use
cases), `page/` (Compose screens + view models), `ui/` (shared components,
theme). Platform-specific code lives in `androidMain/` / `iosMain/`.

## Build & run

```bash
# Server (JVM)
./gradlew :server:run

# Android app
./gradlew :app:android:assembleDebug

# Web app
cd web && bun install && bun run dev
cd web && bun run check   # svelte-check type checking
```

iOS is built from Xcode via [app/ios](app/ios).

> **Agents: never start or run anything yourself** (no `./gradlew run`, no
> `bun run dev`, no server, app, emulator or dev-server launches). The user
> keeps the relevant services running. If something you need is offline or not
> reachable, **ask the user to start it** instead of starting it yourself.

## Coding rules

These rules are mandatory. Prefer following existing patterns in neighbouring
files over inventing new ones.

### Ktor routing

- **All routing structure lives in
  [installRouting.kt](server/src/main/kotlin/es/jvbabi/trails/routes/installRouting.kt).**
  This is the *only* place `route(...) { }` may be used, and every route path
  must be declared there explicitly. This keeps the full API surface visible in
  a single file.
- Individual handler files (e.g.
  [`routes/devices/item/getItem.kt`](server/src/main/kotlin/es/jvbabi/trails/routes/devices/item/getItem.kt))
  define the endpoint logic as extension functions (`get`/`post`/`webSocket`/…
  or plain `suspend fun ApplicationCall`), and are *wired in* from
  `installRouting.kt`. Do not add `route(...)` blocks inside handler files.

### Serialization

- Every `@Serializable` class must annotate **all** properties with
  `@SerialName`, giving the explicit wire name. Never rely on the implicit
  Kotlin property name. Example:

  ```kotlin
  @Serializable
  data class Device(
      @SerialName("id") val id: Uuid,
      @SerialName("friendly_name") val friendlyName: String,
      @SerialName("owner_id") val ownerId: Uuid,
  )
  ```

  Wire names use `snake_case`; Kotlin properties use `camelCase`.

- We don't use enums for external communication, prefer a sealed class with @Serializable and @SerialName for its subclasses

### Comments & documentation

- All comments and KDoc must be written in **English** — always, without
  exception. This holds even when the conversation with the user is in another
  language and even when neighbouring code still contains non-English comments
  (those are legacy; translate them when you touch them, never match them).
- Add KDoc / Javadoc-style documentation comments where they add value
  (public APIs, non-obvious behaviour, invariants). Don't document the obvious.

### Labels

Three labels say which part of the system a change belongs to:
`project:app`, `project:server` and `project:webapp`. Several may apply at once.

They are not documentation, they steer the release
([deploy.yaml](.github/workflows/deploy.yaml)):

| Label on the pull request        | Effect on a merge to `main`                          |
|----------------------------------|------------------------------------------------------|
| `project:app`                    | builds the APKs and publishes a GitHub release       |
| `project:server` / `project:webapp` | builds and pushes the Docker image                |
| none                             | builds nothing                                       |

Label **both the issue and the pull request**: the pull request label decides
what gets built, the issue label decides what the changelog shows (see below).

### Commits

Commit subjects follow:

```
{feat|fix|chore|docs|refactor|…}(<modules>/<areas> #<issue ID>): <description>
```

- `<modules>` — the modules the change touches (`app`, `server`, `webapp`,
  `api`, `build`, …). Several are separated by commas.
- `<areas>` — the feature areas inside those modules (`database`, `snapshot`,
  `share`, `devices`, …). Several are separated by commas. Drop the `/<areas>`
  part when the change isn't tied to one.
- `#<issue ID>` — the issue the work belongs to. Omit it when there is none.
- `<description>` — imperative, capitalised, no trailing period.

Examples:

```
feat(app,server/database,snapshot #9): Add unique ID to data snapshots
feat(app/database,snapshot #9): Add is_synced column to data_snapshot
refactor(webapp/share #5): Move emitted-share components to their own folder
feat(build): Integrate BuildKonfig for Werkbank token management
```

### Changelog

Every feature branch needs a changelog entry for the issue it closes. The entry
lives in **`docs/changelog/issues/<issue ID>/changelog.<type>.json`**, where
`<type>` comes from the GitHub issue type. Copy the matching file from
[`docs/changelog/issues/_template/`](docs/changelog/issues/_template) and fill it
in.

`.github/check_changelog.main.kts` verifies this on every pull request (see
[check_changelog.main.kts](.github/check_changelog.main.kts)); the issues are
taken from the pull request's closing references, falling back to the branch name
(`feat/15-add-minimal-movement` → `#15`).

| Issue type | File name                 | Entry    | `title`      | `description` |
|------------|---------------------------|----------|--------------|---------------|
| Feature    | `changelog.feature.json`  | required | required     | required      |
| Bug        | `changelog.bug.json`      | optional | not allowed  | required      |
| Task       | `changelog.task.json`     | optional | not allowed  | optional      |

- The file name must match the issue type — a `changelog.bug.json` on a Feature
  issue fails the check. The old flat `changelog.json` is no longer read.
- `title` is **only** for features; on a Bug or Task it fails the check, because
  fixes and tasks are rendered as one-liners and the title would be dropped.
- Every text must be a non-empty string. Features get a title plus a full
  description, fixes and tasks a single-line description.
- Localizations go under `localized.<language>` (currently `de`). A localization
  may override only some fields; anything it leaves out falls back to the
  top-level (English) text. The top-level text is always English.
- A missing entry is an error for features and a warning for bugs and tasks — but
  add one anyway whenever the change is user-facing. A task without a
  `description` is silently left out of the released changelog.

```json
{
  "title": "Add changelog support",
  "description": "Issues now have a changelog section.",
  "localized": {
    "de": {
      "title": "Changelog-Unterstützung",
      "description": "Issues haben jetzt eine Changelog-Sektion."
    }
  }
}
```

At release time [generate_changelog.main.kts](.github/generate_changelog.main.kts)
collects the entries for all issues referenced by the commits since the last
release and renders them under *Features*, *Fixes* and *Other changes*. Keep the
JSON valid — a broken file fails the release, which is exactly what the pull
request check is there to catch early.

**Only issues labelled `project:app` make it into the changelog.** A release
ships the app and its changelog is read by the app itself, so a server or web app
change would tell users about something they cannot see. An issue labelled
`project:app` *and* `project:webapp` still counts as an app change. An issue with
no label at all counts as none and is left out with a warning — so label the
issue, not just the pull request. Write the entry anyway: nothing is lost, it is
simply not published to app users.

### Internationalization (i18n)

No user-facing text may be hardcoded in a component or composable — every
display string goes through the translation layer. App and web app both ship
**English and German**.

- **English is the base language.** Keys and the default values are English;
  German is a translation. This matches the English-only rule for comments,
  KDoc and changelog base texts.
- **The language is auto-detected** — from the OS locale in the app, from the
  browser locale in the web app. There is no in-app language picker; users
  switch language in their system or browser settings.
- **Missing translations fall back to English**, never to a raw key.
- **Dates, times and relative durations are localized too.** They must never
  render German month names or a `dd.MM.yyyy` pattern in an English UI. Use
  `nl.jacobras:Human-Readable` in the app and the active `dayjs` locale (or
  `Intl.*`) on the web — never a hardcoded format string.
- **Keys may be explicit and descriptive** — don't compress them to save
  characters. Where the format can nest, express their hierarchy structurally so
  a shared prefix is written once instead of on every sibling: the web catalogues
  put a `rename` object inside `devices` and address its leaves as
  `devices.rename.title`, `devices.rename.description` and
  `devices.rename.placeholder` (that is what the nested groups under *Web* below
  are for). Compose XML resource names can't nest, so the app spells the same
  hierarchy out flat and prefixed — `devices_rename_title`.
- **Key names are `snake_case`** on both platforms — every path segment and every
  leaf: `devices.rename.title`, `shares.shared_with_me`, never `renameTitle` or
  `sharedWithMe`.

#### Web ([`web/`](web))

Uses [svelte-i18n](https://github.com/kaisermann/svelte-i18n).

**One JSON file per language**, at `web/src/lib/i18n/locales/<language>.json`.
Every catalogue holds the complete key set for its language; they are added
eagerly in `web/src/lib/i18n/index.ts` so a server-rendered page never ships
raw message keys.

Catalogues use **nested groups**, not flat dotted keys:

```json
{
  "user": {
    "email": "E-Mail",
    "phone": "Phone"
  }
}
```

instead of

```json
{
  "user.email": "E-Mail",
  "user.phone": "Phone"
}
```

The lookup path (`$_("user.email")`) is identical either way, but the nested
form keeps the catalogue readable and diffable.

The top level of each catalogue is the feature area (`devices`, `shares`,
`auth`, …). Inside it, **texts owned by a dialog go under a `dialogs` object**,
keyed by the dialog — `devices.dialogs.rename.title`,
`emitted_shares.dialogs.delete.description` — which keeps them apart from the
texts the page itself renders. A group whose messages are all page-level
(`emitted_shares.link`, `emitted_shares.badge`) has no `dialogs` object at all.

**The key passed to `$_` doesn't have to be a string.** svelte-i18n also takes a
descriptor object, which is how you supply a `default` or pick the locale
explicitly:

```svelte
{$_({id: "user.email", default: "E-Mail"})}
```

The key also doesn't have to be a literal — a variable, a constant or a
conditional is fine, and is the normal way to map state onto messages:

```svelte
{$_(charging ? "battery.level.charging" : "battery.level.not_charging", {values: {percentage}})}
```

#### App ([`app/shared/`](app/shared))

Uses Compose Multiplatform resources with a generated class for static access
(`Res.string.<key>`), see the
[Compose localization docs](https://kotlinlang.org/docs/multiplatform/compose-localize-strings.html#generate-class-for-static-access).
Strings live under `app/shared/src/commonMain/composeResources/`:
`values/strings*.xml` (English, the default) and `values-de/strings*.xml`
(German). Prefer splitting the strings into several XML files per feature area;
one file per language is acceptable if the toolchain doesn't pick up the extra
files.

XML string names can't nest, so write the hierarchy out flat, prefixed by feature
area: `devices_rename_title`, `devices_rename_description`.

### Web tooling

- Always use **bun** for the Svelte/`web` project (`bun install`, `bun run …`,
  `bunx …`). Never use `npm`, `pnpm` or `yarn`.

## Notes

- The repo's top-level `README.md` is the default Kotlin Multiplatform template
  and does not reflect the current module layout — trust this file instead.
- Server runtime data (SQLite DB, JWT secret, config) lives under
  `server/data/` and is environment-specific — do not commit changes to it.

# App
## Design
We use Lucide icons for the app. They need to be converted to android vector drawables and placed in the composeResources folder.
