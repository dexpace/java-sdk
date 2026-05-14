# Style-Strictness Follow-Ups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the five follow-ups explicitly deferred in commit `5085d74` (the style-compliance pass) and the earlier `4e53c1d` (the dep bump pass), so the SDK builds under strict-mode Kotlin, has passing ktlint + detekt with `ignoreFailures = false`, has a working publishing path, and the JDK 25 / detekt incompatibility is resolved.

**Architecture:** Five sequential commits. Commits 1-3 are mechanical / low-risk; commits 4-5 involve real configuration decisions that may need user input mid-execution. Each commit is independently revertable. Within a commit, parallel implementer groups dispatch where files are disjoint.

**Tech Stack:** Kotlin 2.3.21, JVM target Java 8 for sdk-core / sdk-io-okio3 / sdk-async-coroutines / -reactor / -netty; Java 21 for sdk-async-virtualthreads. Build via Gradle 9.3.1.

**Origin context:** Deferred items called out in commit `5085d74`'s body:
- `allWarningsAsErrors = true` commented out — codebase has implicit-public declarations
- `-Xexplicit-api=strict` commented out — same reason
- ktlint / detekt `ignoreFailures = true` — many existing violations
- detekt task disabled on `sdk-async-virtualthreads` — detekt 1.23.6 / JDK 25 incompatibility
- Publishing target is `build/staging-repo` local only; `signing.isRequired = false`

**Pre-session check:** Before starting, run:
```bash
git log --oneline -5
git status --short
./gradlew clean build --console=plain
```
to confirm the working tree is clean and the build is green at the head this plan was written against (`5085d74`).

---

## Commit 1 — Explicit visibility modifiers across the SDK

Goal: add explicit `public` / `internal` / `private` to every declaration in `sdk-core` Kotlin code and all five adapter modules, so `-Xexplicit-api=strict` compiles cleanly. Then flip the compiler flag.

### Task 1.1: Survey implicit-public declarations

**Files:**
- All `*.kt` under `sdk-core/src/main/kotlin/`, `sdk-io-okio3/src/main/kotlin/`, `sdk-async-coroutines/src/main/kotlin/`, `sdk-async-netty/src/main/kotlin/`, `sdk-async-reactor/src/main/kotlin/`, `sdk-async-virtualthreads/src/main/kotlin/`

- [ ] **Step 1: Enable explicit-api mode temporarily to enumerate violations**

  In root `build.gradle.kts`, inside `plugins.withId("org.jetbrains.kotlin.jvm")` → `tasks.withType<KotlinCompile>().configureEach { compilerOptions { … } }`, uncomment the `freeCompilerArgs.add("-Xexplicit-api=strict")` line.

- [ ] **Step 2: Compile and capture warnings**

  Run:
  ```bash
  ./gradlew :sdk-core:compileKotlin --console=plain 2>&1 | tee /tmp/explicit-api-warnings.log | tail -50
  ```
  Then repeat for `:sdk-io-okio3:compileKotlin`, `:sdk-async-coroutines:compileKotlin`, `:sdk-async-netty:compileKotlin`, `:sdk-async-reactor:compileKotlin`, `:sdk-async-virtualthreads:compileKotlin`.

  Expected: compilation succeeds but emits warnings of the form `warning: visibility must be specified in explicit API mode` with file:line for every offending declaration.

  **Gotcha**: with `allWarningsAsErrors = false` (current state) compilation still passes despite the warnings; the warning list is what you need.

- [ ] **Step 3: Categorize by module + count**

  Parse the warning log into per-module counts. Use:
  ```bash
  grep "visibility must be specified" /tmp/explicit-api-warnings.log | awk -F: '{print $1}' | cut -d/ -f1-2 | sort | uniq -c
  ```
  to get a count per module. Note any files with many violations — those are good candidates for batched edits.

### Task 1.2: Apply explicit visibility per module (parallel)

Dispatch four implementer subagents in parallel, one per area. **Each owns disjoint files; no collisions.**

**Group V-A** owns: `sdk-core/src/main/kotlin/org/dexpace/sdk/core/io/`, `http/common/`, `http/request/`, `http/response/`, `http/context/`, `http/auth/`, `http/paging/`, `http/sse/`, `serde/`, `util/`, `generics/`, `model/`, `config/`.

**Group V-B** owns: `sdk-core/src/main/kotlin/org/dexpace/sdk/core/client/`, `instrumentation/`, `http/pipeline/`, `http/pipeline/steps/`, `testFixtures/`.

**Group V-C** owns: all five adapter modules' production Kotlin source.

**Group V-D** owns: documenting the visibility convention (a paragraph in CLAUDE.md or a new `docs/visibility.md`) — what counts as `public` (anything exported across module boundary that consumers may rely on), what counts as `internal` (implementation details kept Kotlin-only), what gets `@JvmSynthetic internal` (so Java can't even see it via name mangling).

- [ ] **Step 1 (per group): Walk the warning list for your owned files**

  For each warning, choose the right modifier:
  - **Public API surface** (companion-object factories, top-level extensions intended for callers, public data classes, public interfaces) → add explicit `public`
  - **Implementation detail** (private helpers exposed only because Kotlin's default-public is implicit) → add `internal` or `private`
  - **Test seam** (currently used by tests in same module but not part of the SDK contract) → `internal`

  Default rule of thumb: if the file is in a package that the project's KDoc or README documents as "public API", default to `public` for top-level/class-level declarations; default to `private` for helpers; default to `internal` for cross-class same-package helpers.

- [ ] **Step 2 (per group): Re-run compilation, fix any remaining warnings**

  ```bash
  ./gradlew :sdk-core:compileKotlin --console=plain
  ```
  Repeat until no `visibility must be specified` warnings remain in your owned files.

- [ ] **Step 3 (per group): Run module tests**

  ```bash
  ./gradlew :sdk-core:test --console=plain
  ```
  (Or the adapter module's test target.) Visibility changes shouldn't break tests; if a test in the same module breaks because something became `internal` instead of `public`, the test was relying on the wrong contract — either bump visibility (with justification) or rewrite the test against the public surface.

### Task 1.3: Flip `-Xexplicit-api=strict` to `error`

- [ ] **Step 1: Change the compiler flag mode**

  In root `build.gradle.kts`, change `-Xexplicit-api=strict` (which emits warnings) to `-Xexplicit-api=strict` already at error level under `allWarningsAsErrors`, OR explicitly: use the Kotlin Gradle plugin's `explicitApi()` DSL call instead:

  ```kotlin
  extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
      jvmToolchain(8)
      explicitApi()  // equivalent to -Xexplicit-api=strict at warning level
  }
  ```

  Note that `explicitApi()` emits warnings by default; pair with `allWarningsAsErrors = true` (next task) to make it fail the build.

- [ ] **Step 2: Verify clean build**

  ```bash
  ./gradlew clean build --console=plain
  ```
  Expected: BUILD SUCCESSFUL across all six modules.

### Task 1.4: Commit Commit 1

- [ ] **Step 1: Stage and commit**

  ```bash
  git add sdk-core/src/main/kotlin sdk-io-okio3/src/main/kotlin sdk-async-coroutines/src/main/kotlin sdk-async-netty/src/main/kotlin sdk-async-reactor/src/main/kotlin sdk-async-virtualthreads/src/main/kotlin build.gradle.kts
  # Plus CLAUDE.md / docs/visibility.md if Group V-D added documentation
  git commit -m "$(cat <<'EOF'
  chore: explicit visibility modifiers + enable explicit-api mode

  Adds the missing public/internal/private modifiers on every Kotlin
  declaration that previously relied on Kotlin's default-public
  semantics. Enables Kotlin's explicit-api strict mode in the build so
  future contributors must declare visibility.

  Done as one pass across the SDK to unblock the strict-mode flips
  staged in commit 5085d74. No behavior change; tests untouched.
  EOF
  )"
  ```

---

## Commit 2 — Flip `allWarningsAsErrors = true`

Goal: with the visibility cleanup landed, no more warnings should exist. Flip the compiler to treat any remaining warning as an error.

### Task 2.1: Audit remaining warnings

- [ ] **Step 1: Run all module compiles with full warning output**

  ```bash
  ./gradlew clean build --console=plain 2>&1 | grep "^w:" | sort -u
  ```
  Lists every unique warning. After Commit 1 visibility cleanup, expect zero `visibility must be specified` warnings.

  Surviving warnings may include:
  - Deprecation warnings (e.g. existing test using `URL(String)` constructor — known to appear in `VirtualThreadsTest.kt`).
  - "Variable never used" warnings.
  - Java/Kotlin interop warnings.

### Task 2.2: Fix or suppress remaining warnings

- [ ] **Step 1: For each warning, fix at source**

  Preferred: rewrite the code to eliminate the warning. Don't suppress.

- [ ] **Step 2: If a warning genuinely cannot be removed, suppress narrowly**

  Use `@Suppress("WarningName")` at the smallest possible scope (method or class). NEVER `@file:Suppress(…)` unless every declaration in the file truly needs the suppression. Add a one-line comment explaining why the suppression is necessary.

### Task 2.3: Enable `allWarningsAsErrors`

- [ ] **Step 1: Edit root `build.gradle.kts`**

  Change the `compilerOptions { … }` block inside `plugins.withId("org.jetbrains.kotlin.jvm")` to:
  ```kotlin
  compilerOptions {
      jvmTarget.set(JvmTarget.JVM_1_8)
      allWarningsAsErrors.set(true)
  }
  ```
  (Remove the existing `// TODO: re-enable allWarningsAsErrors` comment.)

- [ ] **Step 2: Verify clean build**

  ```bash
  ./gradlew clean build --console=plain
  ```
  Expected: BUILD SUCCESSFUL. If anything fails, it's a leaked warning to fix.

### Task 2.4: Commit

- [ ] **Step 1: Stage and commit**

  ```bash
  git add build.gradle.kts <any .kt files modified to clear warnings>
  git commit -m "chore: enable allWarningsAsErrors after warning cleanup"
  ```

---

## Commit 3 — ktlint + detekt: triage findings, flip `ignoreFailures = false`

Goal: stop suppressing ktlint and detekt findings.

### Task 3.1: Auto-format with ktlint

- [ ] **Step 1: Run ktlint format**

  ```bash
  ./gradlew ktlintFormat --console=plain
  ```
  ktlint auto-fixes most violations (import ordering, indent, trailing whitespace, etc.). The auto-format pass should resolve the bulk of violations without manual intervention.

- [ ] **Step 2: Check remaining violations**

  ```bash
  ./gradlew ktlintCheck --console=plain 2>&1 | tail -50
  ```
  Remaining violations are ones ktlint can't auto-fix (e.g. line-length-over-120, naming-convention misalignment).

- [ ] **Step 3: Fix remaining violations manually**

  For each, edit the offending file. Common patterns:
  - Long lines → break at a natural boundary (operator, function-call arg).
  - Naming → rename per ktlint's rule.

### Task 3.2: Triage detekt findings

- [ ] **Step 1: Run detekt and review the HTML report**

  ```bash
  ./gradlew detekt --console=plain
  ```
  Output is in `build/reports/detekt/`. Open `<module>/build/reports/detekt/detekt.html` per module.

- [ ] **Step 2: For each finding, decide fix or suppress**

  - Real code issues → fix at source.
  - Style preferences the project disagrees with → add to `config/detekt.yml` to disable that rule (with a one-line comment explaining why the rule is off).

- [ ] **Step 3: Re-run detekt until clean**

  ```bash
  ./gradlew detekt --console=plain
  ```
  Expected: no findings.

### Task 3.3: Flip `ignoreFailures = false`

- [ ] **Step 1: Edit root `build.gradle.kts`**

  In the `subprojects { }` block where ktlint / detekt are configured, change `ignoreFailures = true` → `ignoreFailures = false` for both. Remove any related TODO comments.

- [ ] **Step 2: Verify**

  ```bash
  ./gradlew clean build --console=plain
  ```
  Expected: BUILD SUCCESSFUL.

### Task 3.4: Commit

- [ ] **Step 1: Stage and commit**

  ```bash
  git add .
  git commit -m "chore: ktlint + detekt strict mode after triage pass"
  ```

---

## Commit 4 — detekt JDK 25 compatibility on `sdk-async-virtualthreads`

Goal: get detekt running on the only module currently skipping it.

### Task 4.1: Investigate the incompatibility

- [ ] **Step 1: Re-enable detekt on `sdk-async-virtualthreads` and capture the error**

  Remove the `tasks.named("detekt") { enabled = false }` (or equivalent) workaround in `sdk-async-virtualthreads/build.gradle.kts`. Run:
  ```bash
  ./gradlew :sdk-async-virtualthreads:detekt --console=plain
  ```
  Capture the stack trace.

- [ ] **Step 2: Search detekt's GitHub issues for the JDK 25 incompatibility**

  The known compatibility matrix is at https://detekt.dev/docs/intro. Detekt 1.23.x may not support JDK 25 fully; 1.24.x or 2.0.x may. Identify the minimum detekt version that supports JDK 25.

- [ ] **Step 3: Pick a path**

  Options:
  - (a) Upgrade detekt to a version that supports JDK 25 (update `gradle/libs.versions.toml`).
  - (b) Configure detekt to run with a different Java version specifically for this module (use `tasks.named<Detekt>("detekt") { jvmTarget = "21" }` — but check if detekt accepts that).
  - (c) Skip detekt on this module only, with a clear comment + tracking issue link.

  Pick the cleanest option that works in the current ecosystem.

### Task 4.2: Apply the chosen fix

- [ ] **Step 1: Implement the chosen path**

  If (a): bump `detekt` version in `gradle/libs.versions.toml`, re-run detekt on all modules.

- [ ] **Step 2: Verify**

  ```bash
  ./gradlew :sdk-async-virtualthreads:detekt --console=plain
  ./gradlew detekt --console=plain
  ```
  Expected: SUCCESS, no findings on any module.

### Task 4.3: Commit

```bash
git add gradle/libs.versions.toml sdk-async-virtualthreads/build.gradle.kts <any other touched files>
git commit -m "chore: enable detekt on sdk-async-virtualthreads under JDK 25"
```

---

## Commit 5 — Publishing configuration (user input needed)

Goal: replace the local staging-repo + `signing.isRequired = false` placeholders with a real publishing setup.

**This commit requires user-supplied information**: maven group coordinates, repository URL, signing key configuration. Pause when starting this commit and ask the user for:

1. **Maven Central / GitHub Packages / private repo?** (where artifacts publish)
2. **Group coordinates**: confirm `org.dexpace` is the right group, decide on per-module artifactIds (default would be the module directory name).
3. **License**: e.g. Apache-2.0, MIT — needed in the POM.
4. **SCM**: confirm `https://github.com/dexpace/java-sdk` is the canonical SCM URL.
5. **Developers**: who to list in the POM.
6. **GPG signing key**: how should CI obtain it? (Environment variable / Gradle property / external secret manager.)

### Task 5.1: Update POM metadata in every subproject's publishing block

- [ ] **Step 1: For each `<module>/build.gradle.kts`, fill in the `pom { … }` block**

  Replace the existing `// TODO: set url, licenses, developers, scm` with concrete entries based on user answers. Example shape:
  ```kotlin
  pom {
      name.set(project.name)
      description.set("Dexpace Java SDK — ${project.name}")
      url.set("https://github.com/dexpace/java-sdk")
      licenses {
          license {
              name.set("Apache-2.0")
              url.set("https://www.apache.org/licenses/LICENSE-2.0")
          }
      }
      developers {
          developer {
              id.set("dexpace")
              name.set("Dexpace SDK Team")
              email.set("…")
          }
      }
      scm {
          connection.set("scm:git:https://github.com/dexpace/java-sdk.git")
          developerConnection.set("scm:git:ssh://github.com/dexpace/java-sdk.git")
          url.set("https://github.com/dexpace/java-sdk")
      }
  }
  ```

### Task 5.2: Replace the local repo with the real target

- [ ] **Step 1: Edit each module's `publishing.repositories { … }`**

  Replace:
  ```kotlin
  maven {
      name = "local"
      url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
  }
  ```
  with whatever target the user chose. Example for Sonatype OSSRH:
  ```kotlin
  maven {
      name = "ossrh"
      url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
      credentials {
          username = project.findProperty("ossrhUsername") as String? ?: System.getenv("OSSRH_USERNAME")
          password = project.findProperty("ossrhPassword") as String? ?: System.getenv("OSSRH_PASSWORD")
      }
  }
  ```

### Task 5.3: Configure signing

- [ ] **Step 1: Edit each module's `signing { … }` block**

  Switch from the local placeholder to either in-memory GPG (CI-friendly) or a keyring (developer-friendly). Example for in-memory:
  ```kotlin
  signing {
      isRequired = (System.getenv("CI") == "true")
      val signingKey = project.findProperty("signing.key") as String? ?: System.getenv("SIGNING_KEY")
      val signingPassword = project.findProperty("signing.password") as String? ?: System.getenv("SIGNING_PASSWORD")
      if (signingKey != null && signingPassword != null) {
          useInMemoryPgpKeys(signingKey, signingPassword)
      }
      sign(publishing.publications["library"])
  }
  ```

### Task 5.4: Verify publishing dry-run

- [ ] **Step 1: Smoke-test by publishing to the local repo**

  ```bash
  ./gradlew publishAllPublicationsToLocalRepository --console=plain
  ```
  (Or whatever the local-equivalent task is after Task 5.2's repo swap.) Confirm artifacts appear with the right group/artifact/version coordinates.

- [ ] **Step 2: Commit**

  ```bash
  git add <all module build.gradle.kts>
  git commit -m "chore: real publishing config — POM metadata, repo target, signing"
  ```

---

## Final verification (after all five commits)

- [ ] **Full clean build**

  ```bash
  ./gradlew clean build --console=plain
  ```
  Expected: BUILD SUCCESSFUL with strict mode + warnings-as-errors + ktlint/detekt all passing.

- [ ] **Test count + coverage**

  ```bash
  ./gradlew koverXmlReport --console=plain
  find sdk-core sdk-io-okio3 sdk-async-* -path "*/build/test-results/test/*.xml" \
    | xargs grep -h "<testsuite " 2>/dev/null \
    | python3 -c "
  import sys, re
  total = sum(int(re.search(r'tests=\"(\d+)\"', l).group(1)) for l in sys.stdin if 'tests=' in l)
  print(f'Total tests: {total}')
  "
  ```
  Expected: total tests should match or exceed the baseline from commit `5085d74`.

- [ ] **Push**

  ```bash
  git push origin main
  ```

---

## Housekeeping items (separate from the five follow-ups)

These were untracked / drifted in the working tree at the time this plan was written. Decide whether to address as one-off cleanup commits before or after the five follow-ups:

- `styleguide` submodule pointer drift. The submodule's HEAD on disk is ahead of what the parent repo's gitlink records. Either: (a) `cd styleguide && git checkout <pinned-rev>` to revert to the pinned rev; (b) commit the drifted gitlink with a message explaining the bump; (c) document in `.gitmodules` the intended submodule rev range.
- `sdk-core/hs_err_pid14233.log` — JVM crash log from the parallel-races early in the original session. Delete it: `rm sdk-core/hs_err_pid14233.log`. Add `hs_err_pid*.log` to `.gitignore` if not already.
- `.kotlin/` cache directory — should already be in `.gitignore`; verify.
- `java-sdk.iml` — IntelliJ IDE file; add to `.gitignore` if not already.
- `sdk-core/src/main/java/` — pre-existing untracked tree (the legacy Java compat layer). NOT a follow-up to address here; per CLAUDE.md this is generated code intentionally not in the SDK's hand-written surface. Verify the `.gitignore` covers it.

---

## Risks / things the executor should know

- **Commit 1 visibility cleanup is the biggest unit of work.** It will touch dozens of files. The parallel-implementer dispatch is essential — don't try to do it serially.
- **`explicitApi()` DSL vs `-Xexplicit-api=strict` flag**: the DSL is the supported way in modern Kotlin Gradle plugin; the flag is what the previous pass commented out. Either works but DSL is preferred.
- **ktlint auto-format may touch many files.** Don't be alarmed by a large diff after `ktlintFormat`; review carefully.
- **detekt configuration churn**: enabling rules one by one is tempting but tedious. Better to accept detekt's default ruleset (already on via `buildUponDefaultConfig = true`) and disable specific rules in `config/detekt.yml` as needed.
- **Publishing config (Commit 5) requires real user decisions.** If the user is not present, the implementer should stop, write a NEEDS_CONTEXT report, and wait — don't guess coordinates / URLs.
- **Coverage may regress** after the visibility cleanup if formerly-public test seams become `internal` and the tests can no longer call them. Watch for this and either bump visibility back with a `@VisibleForTesting`-style comment, or rewrite the tests against the genuine public surface.

## Out of scope (intentionally not in this plan)

- Adding new tests beyond what the existing tests require (this is a hygiene pass, not a feature pass).
- Refactoring code beyond the visibility / formatting / lint cleanup.
- Bumping any dependency versions (the previous dep-bump pass landed in `4e53c1d`).
- Changing the public API surface (the previous style-compliance pass landed redesigns in `f70c18c`; further breaking changes are not in this plan).
- Documentation rewrites (the previous build-hygiene pass landed doc realignment in `6f4b632`).
