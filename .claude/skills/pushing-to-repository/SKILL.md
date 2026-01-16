---
name: pushing-to-repository
description: Safely pushes code to the git repository after running all linting and tests. Use when the user asks to push code, deploy changes, or submit work to the remote repository. Prevents pushing broken code by validating both Kotlin and JavaScript/TypeScript code first.
---

# Pushing to Repository

Safely push code to the remote git repository after ensuring all checks pass.

## Prerequisites

This project uses [Task](https://taskfile.dev) for running builds and tests. The `task pipe` command runs:
- JavaScript/TypeScript linting (`npm run lint`)
- JavaScript/TypeScript unit tests (`node --test`)
- Kotlin linting (`ktlintCheck`)
- Kotlin unit tests (`gradle test`)
- Dependency audits (`npm audit`)

## Push workflow

Copy this checklist and track your progress:

```
Push Progress:
- [ ] Step 1: Check current git status
- [ ] Step 2: Run all validations (task pipe)
- [ ] Step 3: Review validation results
- [ ] Step 4: Push to remote repository
- [ ] Step 5: Verify push success
```

**Step 1: Check current git status**

Before running validations, check the current state:

```bash
git status
```

Verify:
- Which branch you're on
- What changes are staged or committed
- Whether you're ahead of the remote

**Step 2: Run all validations**

Run the comprehensive validation pipeline:

```bash
task pipe
```

This command runs all linting, tests, and audits. It will fail fast if any check fails.

**Step 3: Review validation results**

If `task pipe` fails:
- Read the error output carefully
- Fix the reported issues
- Return to Step 2 and run `task pipe` again
- **ONLY proceed to Step 4 when all checks pass**

If `task pipe` succeeds:
- All linting passed
- All unit tests passed
- All dependency audits passed
- Safe to proceed to Step 4

**Step 4: Push to remote repository**

Once all validations pass, push the code:

```bash
git push
```

If you need to set upstream for a new branch:

```bash
git push -u origin <branch-name>
```

**NEVER use force push to main/master branches.** If force push is needed for other branches, the user should explicitly request it.

**Step 5: Verify push success**

After pushing, verify the operation succeeded:

```bash
git status
```

You should see output indicating your branch is up to date with the remote.

## Important notes

- **Never skip validations**: Always run `task pipe` before pushing
- **Never force push to main/master**: This can break the repository for other developers
- **Handle failures properly**: If `task pipe` fails, fix the issues rather than bypassing the checks
- **Respect hooks**: Do not use `--no-verify` or similar flags unless explicitly requested by the user

## Error handling

**If task command is not available:**

```bash
# Install Task runner
corepack enable
go install github.com/go-task/task/v3/cmd/task@latest
```

**If validations fail:**

Do not proceed with the push. Fix the issues reported by the validation tools and run `task pipe` again.

**If push is rejected:**

Common causes:
- Remote has changes you don't have locally (run `git pull --rebase` first)
- Branch protection rules prevent direct push (create a pull request instead)
- Authentication issues (check your git credentials)

## Alternative: Create pull request instead

If the user wants to create a pull request instead of pushing directly:

1. Complete Steps 1-3 above (validate the code)
2. Push to a feature branch (not main/master)
3. Use the GitHub CLI to create a PR:

```bash
gh pr create --title "Your PR title" --body "Description of changes"
```

This workflow is preferred for collaborative projects with code review processes.
