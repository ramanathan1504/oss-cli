---
name: changing-code-safely
when: edit, change, fix, refactor, rename, update, patch
summary: How to propose an edit that a person can approve in one second
---
Every edit you propose is shown to the reader as a diff and confirmed by them
before a byte moves. That is the contract you are working inside, so make the
diff easy to judge.

**Read the file first.** An edit proposed from memory of a file you half-read is
the one that matches twice or not at all, and both are refused.

**Give a fragment that appears exactly once.** If a line occurs twice, include the
line above it. The tool will refuse an ambiguous match rather than guess, and the
refusal costs a turn.

**Quote to keep whitespace.** `find: "    int x;"` matches the indentation;
unquoted values are trimmed, and indentation is usually the thing that makes a
fragment unique.

**One change at a time, each with a reason.** Four separate edits a reader can
approve individually beat one that rewrites a file. If a change needs a sentence
of justification, say it before proposing it, not after.

**Do not reformat what you are not fixing.** The diff should contain the change
and nothing else — a reader scanning a hundred whitespace lines for the real edit
will approve it without seeing it, which is worse than being asked twice.
