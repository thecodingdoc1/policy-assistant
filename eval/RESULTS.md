# Evaluation results

## Run 1: Nov 3, 2026. Score: 15 of 15

- **Setup:** fresh database; all four demo documents loaded, then procurement-policy version 2
  uploaded. `qwen2.5-coder` (7.6B, Q4_K_M) and `nomic-embed-text` on local Ollama; temperature 0;
  topK 3; no similarity threshold.
- **Raw responses:** `eval/results/run-20261103-170554.jsonl`. Single run, graded by hand against
  the answer key in `EVAL_SET.md`.
- "Distance" is cosine distance (0 = identical direction); lower is a closer match.

| ID | Result | Answer (abridged) | Expected source | Retrieved (rank: document, distance) |
|---|---|---|---|---|
| Q01 | PASS | Division director | procurement v2 | 1: procurement v2 0.340 · 2: procurement v2 0.374 · 3: travel 0.511 |
| Q02 | PASS | Over $25,000 | procurement v2 | 1: procurement v2 0.365 · 2: procurement v2 0.416 · 3: travel 0.474 |
| Q03 | PASS | Within 1 business day | procurement v2 | 1: procurement v2 0.311 · 2: procurement v2 0.331 · 3: hazmat 0.441 |
| Q04 | PASS | Yes, cybersecurity review by IT Security | procurement v2 | 1: procurement v2 0.390 · 2: procurement v2 0.454 · 3: IT 0.473 |
| Q05 | PASS | At most 5% or $500, whichever is lower | procurement v2 | 1: procurement v2 0.265 · 2: procurement v2 0.436 · 3: travel 0.516 |
| Q06 | PASS | 7 years after final payment | procurement v2 | 1: procurement v2 0.398 · 2: procurement v2 0.439 · 3: IT 0.489 |
| Q07 | PASS | $65/day; 75% on day one = $48.75 | travel | 1: travel 0.346 · 2: procurement v2 0.530 · 3: hazmat 0.555 |
| Q08 | PASS | Over $75, except per diem meals | travel | 1: procurement v2 0.389 · **2: travel 0.411** · 3: procurement v2 0.425 |
| Q09 | PASS | 6 hours or more, with VP approval | travel | 1: travel 0.402 · 2: procurement v2 0.482 · 3: hazmat 0.495 |
| Q10 | PASS | Within 30 days; not reimbursed after 60 | travel | 1: travel 0.453 · 2: procurement v2 0.472 · 3: procurement v2 0.499 |
| Q11 | PASS | Every 24 months | hazmat | 1: hazmat 0.369 · 2: procurement v2 0.534 · 3: IT 0.547 |
| Q12 | PASS | Safety Office within 2 hours | hazmat | 1: hazmat 0.358 · 2: IT 0.509 · 3: procurement v2 0.520 |
| Q13 | PASS | IT Service Desk within 1 hour | IT | 1: IT 0.338 · 2: hazmat 0.475 · 3: procurement v2 0.479 |
| Q14 | PASS | Declined: context has nothing on parental leave | (none) | 1: travel 0.438 · 2: IT 0.439 · 3: hazmat 0.469 |
| Q15 | PASS | Declined: context has nothing on a dress code | (none) | 1: hazmat 0.472 · 2: IT 0.514 · 3: procurement v2 0.522 |

## Observations

1. **No superseded text leaked.** Q01-Q04 all gave the version 2 rule, and no version 1 chunk was
   retrieved for any question (retrieval filters on `is_active`).
2. **Q08's right document ranked second.** The answer was still correct because topK 3 included it.
   With topK 1 this question would likely fail. That's a reason not to lower topK just to shorten prompts.
3. **A similarity threshold wouldn't separate in-scope from out-of-scope here.** The out-of-scope
   questions' best matches (0.438, 0.472) are closer than some in-scope best matches (Q10: 0.453).
   Any cut-off that rejects Q14 and Q15 also rejects Q10. Refusal currently comes from the prompt
   template, not from a score.
4. **Out-of-scope answers still list sources.** Sources are "what retrieval supplied", so Q14 shows
   three weak matches. The distances make that visible; a UI could hide sources when the answer is a refusal.
5. **Why temperature 0:** before it was set, the same $40,000 question got the wrong approver in
   about 1 of 6 tries at Ollama's default temperature; at 0 it was right 6 of 6 across both versions.
6. **Limits:** 15 questions by one author over a small, clean corpus; one run; not a benchmark. The
   PO tool isn't covered (tool calling doesn't work on Spring AI 1.0.0-M1 with Ollama).
