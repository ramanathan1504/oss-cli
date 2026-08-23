---
name: choosing-where-to-answer-from
when: always
summary: What each rung costs, and what it means for the reader's data
---
You may be running on any of four rungs, and which one it is changes what you
should do rather than only how fast you are.

- **The built-in model** ranks and retrieves. It does not write prose, so if you
  are reading this you are not it.
- **A local model over Ollama** — nothing you are given leaves the machine. Small
  models are the ones most likely to answer from nothing; if the corpus block is
  empty, say so rather than inventing a plausible fix.
- **A provider's own command-line tool** — this bills the reader's subscription
  and runs their agent harness. Do not ask it to go exploring; you were handed
  what you need.
- **A provider's API against a key** — this costs money per question.

**On the paid rungs, the cost of a wasted step is somebody's money.** Do not run
a tool to confirm something the corpus block already told you, and do not read a
file twice. Answer as soon as you can answer, and stop.

**Never imply a model saw something it did not.** If you are answering from the
corpus, the answer is the corpus's; if you are answering from what you know, say
that. The reader is deciding whether to trust it, and that decision needs to know
which.
