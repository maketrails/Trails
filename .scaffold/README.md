# Scaffold

This project is based on [Scaffold v0.2](https://github.com/Julius-Babies/Scaffold/tree/v0.2).
Files derived from it: `.github/`.

## Parameters

The values the Scaffold components ask the project to fill in.

| Component | Parameter | Value |
| --- | --- | --- |
| `github` | `APP_NAME` | `Trails` |
| `github` | `DOCKER_IMAGE` | `ghcr.io/maketrails/trails` |
| `github` | Server JDK | `25` |
| `github` | Android JDK | `21` |

## Deviations

Everything else that differs from Scaffold v0.2. *Upstream* marks deviations that
would help every project and should move to Scaffold.

| File | Deviation | Reason | Upstream |
| --- | --- | --- | --- |
| `.github/actions/setup-android-project/action.yaml`, `.github/workflows/deploy.yaml` | Inputs `mapbox_public_token`, `mapbox_secret_token`, `play_developer_signing_id`, passed in from `build-android` as secrets `MAPBOX_PUBLIC_TOKEN`, `MAPBOX_SECRET_TOKEN`, `PLAY_DEVELOPER_SIGNING_ID` | The app renders maps with Mapbox (public token in `local.properties`, secret token in `~/.gradle/gradle.properties` for the SDK download) and registers with Play developer verification (`adi-registration.properties`) | no |
| `.github/workflows/deploy.yaml` | `build-server` runs `:server:buildServerJar` instead of `:server:buildFatJar` and moves `server/build/libs/server*.jar` | The server uses its own jar task (`server/build.gradle.kts`) instead of the Ktor fat jar | no |
| `.github/workflows/deploy.yaml` | `build-server` passes `-Pmaven.pkg.github.com.user`/`.token` from `GH_USERNAME`/`GH_REGISTRY_TOKEN`; `build-web` writes an `.npmrc` for `@Julius-Babies` on `npm.pkg.github.com` | Server and web app depend on private packages from GitHub Packages | no |
