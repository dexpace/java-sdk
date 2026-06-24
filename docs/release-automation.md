# Release Automation

Status: **decided**. This document records the release-automation approach for the dexpace
Java SDK and the steps to operate it. It supersedes the open question of whether to adopt
release automation before the first non-alpha tag.

## Decision

Adopt **tag-driven publishing through GitHub Actions**, built directly on the existing
`maven-publish` + `signing` convention plugin. A workflow triggered by a `v*` tag builds the
SDK, signs the artifacts, and publishes them to Maven Central. Versioning and the changelog
stay manual for now.

No additional release framework (release-please, semantic-release, JReleaser, or a third-party
release host) is introduced. The publishing machinery is already in place; what is missing is a
trigger and a credential path, both of which this approach supplies with the tools the repository
already uses (Gradle + GitHub Actions + Sonatype's upstream endpoints).

### What this deliberately does not do

- **No automated version bumping or changelog generation.** The version lives in one place,
  `gradle.properties`, and a release bumps it with a one-line edit and a commit. At the current
  cadence (pre-1.0, infrequent tags) a release-please manifest plus version anchors across the
  README and build scripts is more moving parts than the project benefits from. This can be
  revisited once the tag cadence justifies the automation; the trigger and publish steps below
  do not depend on that choice.
- **No third-party release host.** Publishing goes to Sonatype directly using upstream Gradle
  and GitHub Actions only, matching the constraint in the originating issue.

## Why this approach

The publishing setup is already complete and centralized:

- `build-logic/src/main/kotlin/dexpace.published-module.gradle.kts` is the single convention
  plugin every publishable module applies via `plugins { id("dexpace.published-module") }`. It
  configures the `library` `MavenPublication` from the `java` component, the shared POM
  (name, description, MIT license, developer, SCM), the publish repositories, and signing.
- Nine modules opt in today: `sdk-core`, `sdk-io-okio3`, `sdk-serde-jackson`,
  `sdk-transport-okhttp`, `sdk-transport-jdkhttp`, `sdk-async-coroutines`, `sdk-async-reactor`,
  `sdk-async-netty`, and `sdk-async-virtualthreads`. The two unpublished modules
  (`sdk-shrink-test`, `sdk-example`) simply do not apply the plugin and stay out of releases.
- Coordinates (`group=org.dexpace`, `version`) come from `gradle.properties` and apply to the
  root and every subproject, so a version bump is a single edit.
- Signing is already CI-gated: `isRequired = (System.getenv("CI") == "true")`, and the key
  material is read from `signing.key` / `signing.password` Gradle properties or the
  `SIGNING_KEY` / `SIGNING_PASSWORD` environment variables via `useInMemoryPgpKeys(...)`. An
  in-memory ASCII-armored key is exactly what a CI secret supplies — no keyring file on the
  runner.

Because of that, release automation reduces to two additions: a remote publish repository the CI
job points at, and a tag-triggered workflow that supplies the four secrets. Nothing in the module
build scripts changes.

## Trigger

A release is cut by pushing an annotated tag matching `v*` (for example `v0.1.0`):

```bash
# 1. Bump the version on main (drop the -alpha suffix for the first stable release).
#    Edit gradle.properties: version=0.1.0
git commit -am "chore: release 0.1.0"
git push origin main

# 2. Tag the release commit and push the tag.
git tag -a v0.1.0 -m "Release 0.1.0"
git push origin v0.1.0
```

The tag push is the only thing that triggers a publish. Pushes to `main` and pull requests
continue to run CI only (the existing `.github/workflows/ci.yml`); they never publish.

The tag name and the `version` in `gradle.properties` must agree (`v0.1.0` <-> `0.1.0`). The
release workflow verifies this and fails fast on a mismatch, so a stray tag cannot publish a
stale version.

## Secrets

The release workflow needs four repository secrets in
**Settings -> Secrets and variables -> Actions**:

| Secret | Purpose |
|---|---|
| `SIGNING_KEY` | ASCII-armored PGP private key (the full `-----BEGIN PGP PRIVATE KEY BLOCK-----` block). Consumed by `useInMemoryPgpKeys`. |
| `SIGNING_PASSWORD` | Passphrase for that PGP key. |
| `SONATYPE_USERNAME` | Sonatype Central user-token username (not the portal login). |
| `SONATYPE_PASSWORD` | Sonatype Central user-token password. |

Generating the PGP key (one-time):

```bash
gpg --quick-generate-key "Dexpace SDK Team <releases@dexpace.org>" rsa4096 sign 2y
gpg --armor --export-secret-keys <KEY_ID>            # paste into SIGNING_KEY
gpg --keyserver keys.openpgp.org --send-keys <KEY_ID> # publish so Central can verify
```

The Sonatype token is created on the Central Portal account page; use the generated token
credentials rather than the account password.

## Publish target

The convention plugin currently declares only a `local` staging repository
(`build/staging-repo`) and notes that "CI must override this to publish to a real remote." The
release job supplies that remote. Two equivalent options:

- **Preferred:** point a Maven repository at the Sonatype Central upload endpoint and run
  `publishLibraryPublicationToCentralRepository` per module, with `SONATYPE_USERNAME` /
  `SONATYPE_PASSWORD` as the repository credentials. This keeps everything inside the existing
  `maven-publish` machinery.
- Alternatively, publish each module to the `local` staging directory
  (`publishLibraryPublicationToLocalRepository`) and hand the resulting bundle to the Central
  Portal upload API in a follow-up step.

Either way the artifacts published are exactly the signed `library` publications the convention
plugin already produces — sources jar, javadoc jar, POM, and `.asc` signatures — for the nine
opted-in modules only.

## Steps the workflow runs

A `release.yml` workflow gated on the tag performs, in order:

1. **Check out** the tagged commit (`submodules: recursive`, matching CI — the `styleguide`
   submodule must be present).
2. **Set up JDK 21** (Temurin), the same toolchain CI uses; the foojay resolver auto-provisions
   the JDK 8 / 11 toolchains the cross-compiled modules need.
3. **Verify** that the `v*` tag matches `version` in `gradle.properties`; fail on mismatch.
4. **Build and verify** with `./gradlew build` so a release never ships artifacts that would not
   pass the full quality gate (tests, ktlint, detekt, apiCheck, coverage floor).
5. **Publish** the signed publications to Sonatype Central with `CI=true` set (so signing is
   required) and the four secrets exported as environment variables / Gradle properties.
6. **Release the staging repository** to Maven Central (automatic via the Central Portal once
   the upload validates).

`permissions: contents: read` is sufficient; the workflow does not push commits or create GitHub
Releases. Cutting a GitHub Release with notes, if wanted, stays a manual step alongside the tag.

## Operational checklist for the first non-alpha release

1. Confirm the four secrets are present in repository settings.
2. Confirm the PGP public key is published to a keyserver Central checks.
3. Bump `version` in `gradle.properties` to the release version (drop `-alpha`).
4. Update any version references in `README.md` in the same commit.
5. Merge to `main`; confirm CI is green.
6. Tag the release commit `vX.Y.Z` and push the tag.
7. Watch the release workflow; on success, confirm the artifacts appear on Maven Central.
