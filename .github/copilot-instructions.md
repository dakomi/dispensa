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

### `SESSION_NOTES.md` — structure and format

Sessions are in **chronological order, oldest first**. Each session follows this template:

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

#### Table of Contents

`SESSION_NOTES.md` begins with a **Table of Contents** immediately after the title block. For every session or sub-session, update the ToC.

- Each entry is a Markdown list item: `- [Session N — <title>](<anchor>) *(~<start>–<end>)*`
- Sub-sessions are indented two spaces under their parent entry, with a ` ↳ has sub-sessions` marker on the parent line.
- Include **approximate line ranges** so agents can jump directly to a section using `view_range` instead of reading the whole file.
- The agent navigation note at the top of the ToC is: `> **Agent navigation:** Approximate line ranges are provided for efficient \`view_range\` lookups in this ~<approxLineCount>-line file. Ranges shift slightly as the ToC grows.`
  - Chore: Should line ranges get out of sync, regenerate them by getting all session headers with line numbers `grep -n "^## Session" /home/runner/work/<repo_dir>/<repo_dir>/SESSION_NOTES.md`
- GitHub Markdown anchor generation: lowercase the heading, remove all characters except `a–z`, `0–9`, spaces, and hyphens, then replace spaces with `-`.

#### Sub-session placement

When a session has sub-sessions:

1. Add a blockquote link under the session's `##` header (before `**Date:**`):
   ```
   > **Sub-sessions:** [N.1 — <title>](<anchor>)
   ```
2. Place the sub-session's full section (header, body) **after** the parent session's body and **before** the shared Handoff.
3. There is **one Handoff** at the end — written by whichever session/sub-session ran last. If a sub-session modifies the handoff, prepend a one-line `_(Updated by Session N.x: ...)_` note explaining what changed.

### `PLAN.md` — update task checkboxes:

- Mark completed tasks with `[x]`.
- Add new tasks discovered during the session.
- Keep the overall structure intact; only update statuses and add tasks.

---

## Sub-session numbering and logging

Sometimes a task is a small fix or follow-up that doesn't warrant a full new session entry in `PLAN.md` but still deserves a record in `SESSION_NOTES.md`.

- **If the fix directly builds on the immediately preceding session** (e.g. a bug found while testing the previous session's code): log it as `Session N.1` (or `N.2`, etc.) in `SESSION_NOTES.md` only — do **not** add it to `PLAN.md`.
- **If the fix does not directly build on the last session** (e.g. an unrelated hotfix or a task from a different area): **ask the user** how and whether they would like it logged before writing anything to either file.
