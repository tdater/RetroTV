---
name: android-finish
description: Finish a requested change in RetroTV end to end. Inspect the repo, implement, run unit tests and lint and build the debug APK on GitHub Actions, fix and rerun failures, review the diff, and report what was verified versus what still needs the Android TV.
disable-model-invocation: true
argument-hint: "[change to finish]"
---

# /android-finish

Finish this change: $ARGUMENTS

If no change was given, finish the work in progress: the uncommitted diff plus the current conversation's request. Follow the root `CLAUDE.md`, especially "How to work on this project". This skill doesn't override it.

## 1. Inspect

- Run `git status --short` and `git diff --stat`. Note which uncommitted changes aren't part of this task and leave them alone.
- Read the code you'll touch, plus `gradle/libs.versions.toml`, `app/build.gradle.kts` and `app/src/main/AndroidManifest.xml` if the change involves dependencies, permissions, activities or aliases.
- Builds run on GitHub Actions only: the owner's laptop (4 GB RAM) has no Android toolchain; never try to install Android Studio, a JDK or the SDK on it.
- Work on a branch: `git fetch origin`, then `git switch -c claude/<short-topic> origin/main` (or keep using the current `claude/` branch if this continues earlier work). Mention any older unmerged `claude/` branches.

## 2. Implement

- Make the complete change in source files. Never edit `app/build/`, `.gradle/` or `.kotlin/`.
- Add or extend JVM unit tests in `app/src/test/java/com/aiconsole/retrotv/` for any plain-Kotlin logic (rules, parsers, name matching). These tests can't use `org.json` or Android framework classes.
- Adding a built-in site needs a matching `activity-alias`, string and banner (see `CLAUDE.md`, "Site apps").
- Page-side behaviour (ad blocking, `game-hook.js`, the player page) can be tried without a TV by driving the installed Edge with `playwright-core` from the scratchpad, as `CLAUDE.md` describes.

## 3. Review the diff

Run `git diff` (plus `git status --short` for new files) and check that:
- only intended files changed, and the owner's unrelated changes are intact;
- nothing got left behind: debug logging, commented-out code, hardcoded keys or secrets; nothing from `../RetroTV-signing` is referenced or committed;
- SDK-gated APIs and permissions are guarded, and network failures show an error instead of crashing;
- new controls use `TvClickable`/`TvButton`/`ChoiceRow`, so they work with the remote's D-pad;
- `CancellationException` is rethrown before broad catches, and IO work runs on `Dispatchers.IO`;
- source files have no mojibake in `·`, `…`, `Я` (never rewrite them via PowerShell `Get-Content`/`Set-Content`).

Fix anything you find. Then commit without asking: stage only the files this task changed by explicit path (never `git add -A`), and leave the owner's unrelated changes unstaged.

## 4. Checks and build (CI)

The `Android build` workflow (`.github/workflows/android.yml`) runs `testDebugUnitTest lintDebug assembleDebug` and uploads the APK as the `app-debug` artifact. Pushes to work branches don't trigger it on their own, so start it by hand:

```powershell
$branch = git branch --show-current
git push -u origin $branch
gh workflow run android.yml --ref $branch
Start-Sleep 5
$run = gh run list --workflow android.yml --branch $branch --limit 1 --json databaseId --jq '.[0].databaseId'
gh run watch $run --exit-status        # run in the background; a build takes several minutes
```

- On failure: `gh run view $run --log-failed` shows the failing step's output (test failures, lint errors, compile errors).
- On success, fetch the APK: `gh run download $run -n app-debug -D "$env:USERPROFILE\Downloads\RetroTV-$branch-$run"`, and note the run's page (`gh run view $run --json url --jq .url`), where the owner can download it from any device.
- There are no instrumented tests; say so in the report.
- If `gh` isn't available (a cloud session, for example), push the branch and open a pull request against `main` instead; the workflow runs on pull requests.

If a check fails:
1. Read the actual error.
2. Fix the root cause in source. Don't weaken, skip or delete tests, and don't add lint suppressions just to go green.
3. Commit the fix, push, start a new run, and rerun the full checks before finishing.

Lint warnings that predate this change don't block the run, but new errors do. Mention any new warnings.

Pushing a `claude/` work branch for CI is fine without asking. Once the checks are green, if the owner discussed or asked for this change, merge it into `main` and push without asking again (`git switch main; git pull --ff-only; git merge --ff-only <branch>; git push`; if it won't fast-forward, rebase the branch on `origin/main` and rerun the checks first). Otherwise offer to merge. Create `v*` tags only when asked; a release also updates the features page in `../RetroTV-releases`.

## 5. Report

End with exactly these sections, kept short:

- **Changed:** files and what each change does, plus the commit hash and the branch.
- **Verified:** each check that ran, the CI run URL, and its real result (tests passed/failed with counts, lint errors, build result), plus any Playwright page test.
- **APK:** the full local path and the run page to download it from, or "not built" and why.
- **Not verified, needs the TV:** only the specific on-device steps this change needs. For example: "On the TV, open a site with the remote, start a NES game, confirm the controller works in the built-in player."

A green build and passing JVM tests prove the code compiles and the pure-Kotlin logic works. They say nothing about WebView behaviour on the TV, the remote, controllers or game sites, so never phrase them as if they do.
