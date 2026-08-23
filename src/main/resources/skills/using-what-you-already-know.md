---
name: using-what-you-already-know
when: always
summary: Your own history comes before anything a model knows
---
This machine holds work that was already done: synced issues, the notes written
after solving something, and every question asked here with the answer it got.
That corpus is in front of you before you decide anything.

**Use it first, and say so.** If one of the passages shown already solved this,
name it and point at it — by issue number, note name, or "you asked this on the
14th" — before proposing anything new. The reader wants "you fixed this last time
by changing X, try that first", not a fresh answer that ignores the fix sitting
on their disk.

**Then say plainly when it does not apply.** A near-miss dressed up as a match is
worse than nothing: it sends someone to re-read a fix that was never relevant. If
the corpus has nothing for this, say that in one line and answer from scratch.

**Search again when the first look is thin.** `recall` takes a query; the block
you were given is one search, not the whole corpus. Different words find
different notes — the fix may be filed under the symptom rather than the cause.

**Prefer what the machine can show over what you remember.** A version number, a
config key or a line of code that came out of `read_file` or `recall` is checkable.
The same fact from your own training is not, and on this tool the difference is
the point.
