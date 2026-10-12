---
name: practicum-weekly-log
description: Generates a weekly wrap-up (summary, code excerpts, new understandings/learnings) for the student's work on this project (Resonance-Flow), building toward the required project book (ספר פרויקט). Also assembles the final project book from the accumulated weekly wrap-ups. Trigger this whenever the user asks for a "weekly summary", "wrap-up", "תקציר שבועי", "סיכום שבוע", mentions "פרקטיקום" or "ספר פרויקט", asks to log this week's progress, or asks to compile/assemble the final project book. Use this even if the user only says something like "בואי נסכם את השבוע" or "תעני לי מה עשיתי השבוע" — don't wait for them to name the skill explicitly.
---

# Practicum Weekly Log

Helps the student produce, at the end of every week worked on this project, a self-contained wrap-up that will later be assembled into the final project book (ספר פרויקט). Also handles final assembly.

Two distinct outputs, both maintained every week:
1. **A per-week file** — the raw material for that week, kept separately.
2. **A running cumulative document** — all weeks so far, kept in sync, so the student never has to manually merge things at the end.

Write everything in Hebrew, but keep technical/CS terms (function names, library names, concepts like "API", "recursion", "unit test") in English inline, the way the student naturally mixes them. Don't translate code or code identifiers.

## When to run a weekly wrap-up

Trigger at the natural end of a work week on the project, when the user asks to summarize, log progress, or says something equivalent to "let's close out this week." If it's unclear what happened this week, ask — don't invent progress, code, or learnings the user hasn't actually described. It's fine to ask 2-3 quick questions if the week's content hasn't been shared yet (e.g., "מה התקדם השבוע?", "יש קטע קוד מרכזי שכדאי לכלול?", "מה למדת/גילית החדש?").

Pull from the current conversation first — if the user already described what they built, pasted code, or mentioned something they learned earlier in this session, use that instead of asking again. It's also fine to look at recent git history/diffs in this repo (`git log`, `git diff`) as a memory aid for what happened this week, but confirm with the user before writing it up as fact — commits don't capture intent or things learned.

## Weekly wrap-up structure

For week N, always use this structure (adapt headers lightly to fit what's actually available — don't force empty sections):

```markdown
## שבוע N — [תאריכים אם ידועים]

### מה התקדם
[2-5 sentences, concrete: what was built/fixed/designed this week, at the feature/module level, not a diary]

### קטעי קוד מרכזיים
[1-3 short, meaningful code excerpts — not the whole file. Each with 1 sentence on what it does and why it mattered this week. Use fenced code blocks with the right language tag.]

### הבנות ודברים חדשים שנלמדו
[Concrete technical insights, not generic ("למדתי איך X עובד בפועל", "גיליתי ש-Y", "הבנתי למה עדיף Z על פני W"). Tie each to something specific from this week's work, not a general definition copied from docs.]

### קשיים ופתרונות (אם רלוונטי)
[Optional — include only if the user mentioned a real obstacle and how it was resolved or is still open]
```

## File handling

Maintain two files across the whole practicum, inside `docs/project-book/` at the repo root (create the folder if it doesn't exist yet):

- `docs/project-book/שבוע-N-סיכום.md` — this week's wrap-up alone (create fresh each week; use the actual week number in the filename).
- `docs/project-book/ספר-פרויקט-מצטבר.md` — the cumulative document. Read it first if it exists, then append this week's section under a `## שבוע N` heading, keeping all previous weeks intact. Never regenerate previous weeks' content from memory — only append the new section, byte-for-byte preserving what's already there. If the file doesn't exist yet, create it with a short title header and this week's section as the first entry.

These are ordinary files in the git working tree — after generating/updating them, mention to the user that they're untracked/modified so they can `git add`/commit when ready (don't commit on their behalf unless asked).

## Final assembly — the project book (ספר פרויקט)

When the user asks to assemble/finalize the project book (usually at the end of the practicum), use the cumulative document as the backbone. Since this student's institution has no specific template on file, structure the book using this generic but standard structure for a software-engineering practicum project book, and tell the user explicitly that they should double check it against whatever their instructor/מכללה actually requires (formats vary):

```markdown
# ספר פרויקט — [שם הפרויקט]

## 1. תקציר מנהלים / מבוא
[What the project is, its goal, the tech stack — 1 short section, written fresh, not just week 1's intro]

## 2. רקע וטכנולוגיות
[Architecture overview, main technologies/frameworks used — pull this from what's consistent across the weekly logs, and from the repo itself (e.g. teams/bridge structure) if useful]

## 3. מהלך הפיתוח לפי שבועות
[Insert the full weekly log here, week by week, largely as accumulated — this is the bulk of the book]

## 4. אתגרים מרכזיים ופתרונות
[Synthesized across weeks — pull from the "קשיים ופתרונות" sections rather than re-inventing]

## 5. הבנות ולקחים כלליים
[Higher-level, cross-week takeaways — different from the per-week learnings: what changed in how the student approaches problems, tools, or planning, based on patterns across multiple weeks]

## 6. סיכום ומבט קדימה
[Project status at the end, possible next steps/extensions]

## נספחים
[Full or fuller code excerpts that were trimmed for space in the weekly sections, if the user wants them included]
```

Do not fabricate content for sections 1, 2, 5, or 6 — draft them from what's actually in the weekly logs and ask the user to fill gaps (e.g., the project's original goal, or final status) if it isn't there. Sections 3 and 4 should draw directly from the accumulated weekly material with minimal rewriting — don't shorten the student's own recorded progress and learnings just to make the book tidier.

Write the final assembly to `docs/project-book/ספר-פרויקט-סופי.md`. If the student wants a submittable Word document, convert it to `.docx` using whatever docx-conversion tool/skill is available in the session; otherwise the markdown file is fine for review.
