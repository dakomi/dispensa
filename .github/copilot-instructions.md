# Copilot Instructions — Multi-Session Development

This project is built across a series of agentic sessions.
Each session reads the current state, does its work, and leaves clear handoff notes for the next.

## On every task

1. **Check for `PLAN.md` and `SESSION_NOTES.md`** in the repository root.
   - **If neither exists** → this is Session 1. See the "Starting a new project" section below.
   - **If they exist** → this is a continuation. See the "Continuing a project" section below.

---

## Starting a new project (Session 1)

When `PLAN.md` and `SESSION_NOTES.md` do not yet exist:

1. **Research & understand** the problem statement provided by the user.
2. **Create `PLAN.md`** with:
   - A short project overview.
   - An architecture decisions table (tech stack, key choices, rationale).
   - A phased session plan — break the work into sessions of a sensible size.
     Each session should have a clear goal, a task checklist, and a test requirement.
   - A data flow diagram (ASCII is fine) if the architecture warrants one.
3. **Create `SESSION_NOTES.md`** with a Session 1 section (see format below).
4. **Scaffold the project** — folder structure, config files, dependency manifests, entry points.
5. **Write the Session 1 handoff** at the bottom of `SESSION_NOTES.md`.

---

## Continuing a project (Session N)

When `PLAN.md` and `SESSION_NOTES.md` already exist:

1. Read **all** of `PLAN.md` and `SESSION_NOTES.md` before touching any code.
2. Identify the **next incomplete session** in `PLAN.md` (first session with unchecked tasks).
3. Read the **most recent Handoff section** in `SESSION_NOTES.md` for detailed instructions.
4. Implement the described tasks, following the conventions established in previous sessions.
5. When done, update both files (see below).

---

## Updating the tracking files (every session)

### `SESSION_NOTES.md` — append a new section:

```
## Session N — <short title>

**Date:** YYYY-MM-DD  
**Goal:** One sentence describing the session's objective.

### What was done
- Bullet list of completed tasks and key decisions made.

### Files changed
- `path/to/file` — reason for change

### Test results
- Pass / Fail summary and any notable findings.

### Handoff to Session N+1
- What to do next (be specific).
- Any known issues or blockers.
- Conventions or patterns established this session that must be followed.
```

### `PLAN.md` — update task checkboxes:

- Mark completed tasks with `[x]`.
- Add new tasks discovered during the session.
- Keep the overall structure intact; only update statuses and add tasks.

---

## Sub-session numbering and logging

Sometimes a task is a small fix or follow-up that doesn't warrant a full new session entry in `PLAN.md` but still deserves a record in `SESSION_NOTES.md`.

- **If the fix directly builds on the immediately preceding session** (e.g. a bug found while testing the previous session's code): log it as `Session N.1` (or `N.2`, etc.) in `SESSION_NOTES.md` only — do **not** add it to `PLAN.md`.
- **If the fix does not directly build on the last session** (e.g. an unrelated hotfix or a task from a different area): **ask the user** how and whether they would like it logged before writing anything to either file.
