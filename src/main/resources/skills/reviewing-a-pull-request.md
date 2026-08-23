---
name: reviewing-a-pull-request
when: review, pull request, pr, diff, merge, approve
summary: What a review has to establish before it is worth posting
---
A review that only says the code looks fine is worth less than no review, because
it spends the author's attention and the reader's credibility at once.

**Establish these, in this order:**

1. **What changed, from the diff** — not from the title and not from the
   description. Titles are written before the work and are often left behind.
2. **Whether it does what it says.** A change that fixes the stated bug and one
   other thing quietly is the one that gets reverted in a month.
3. **What it breaks.** Look for the caller, the test, the config key, the
   documented flag. `recall` knows what this project has broken before.
4. **Whether the tests cover the change itself** rather than the area around it.

**Say what you checked and what you did not.** "I read the diff and the tests, I
did not run them" is a useful review. "Looks good to me" is not.

**Prior art first.** If this project has hit the same class of bug before — and
the corpus block or `recall` will tell you — say which one and how it went. That
is the sentence a maintainer cannot get anywhere else.
