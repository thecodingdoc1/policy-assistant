# Evaluation set

15 questions over the fictional demo policies in `demo/documents/`, with the answer key written
**before** any run. The questions are in `eval/questions.tsv`; `eval/run-eval.sh` sends them and
saves the raw responses; grading is by hand against this key.

**Required state:** all four demo documents loaded with `demo/load-documents.sh`, then
`demo/upload-v2.sh`, so **procurement-policy version 2 is active**. Q01-Q04 are version-sensitive:
the version 1 answer is wrong.

## Grading rule

- **Answerable question → PASS** when the answer states the key fact correctly, doesn't contradict
  it or give the outdated version 1 rule, **and** the expected document is among the returned sources.
- **Out-of-scope question → PASS** when the system says the documents don't cover it and doesn't
  invent a policy.
- Anything else is a FAIL. Partial credit isn't given; extra harmless detail is fine.

## Answer key

| ID | Question | Key fact (must be present) | Wrong if it says | Expected source |
|---|---|---|---|---|
| Q01 | What approval is needed for a $40,000 purchase? | Division director ($10,000.01-$50,000 band) | Vice President of Operations (v1 rule) | procurement-policy v2 |
| Q02 | When are three written quotes required for a purchase? | Purchases over $25,000 | Over $10,000 (v1 rule) | procurement-policy v2 |
| Q03 | If an emergency purchase is made before a purchase order exists, how long does the requester have to document it? | 1 business day | 2 business days (v1 rule) | procurement-policy v2 |
| Q04 | Does a $2,000 software subscription need any review besides manager approval? | Yes: cybersecurity review by IT Security, whatever the amount | No other review needed | procurement-policy v2 |
| Q05 | How much can an invoice differ from the purchase order and still be paid? | At most 5% or $500, whichever is lower | | procurement-policy v2 |
| Q06 | How long are procurement records kept? | 7 years (after final payment) | | procurement-policy v2 |
| Q07 | What is the domestic meal per diem, and how much is paid on the first day of a trip? | $65 per day; first day 75% (= $48.75) | | travel-expense-policy |
| Q08 | Above what amount is a receipt required for an expense? | Over $75 | | travel-expense-policy |
| Q09 | When is business class allowed on a flight? | Flights of 6 hours or more, with VP approval | | travel-expense-policy |
| Q10 | What is the deadline for submitting an expense report? | Within 30 days after the trip ends (not reimbursed after 60) | | travel-expense-policy |
| Q11 | How often do hazmat shippers need recurrent training? | Every 24 months | | hazmat-shipping-policy |
| Q12 | How quickly must a hazmat spill be reported? | To the Safety Office within 2 hours (written report within 24 hours) | | hazmat-shipping-policy |
| Q13 | How soon must a lost company laptop be reported? | To the IT Service Desk within 1 hour | | it-equipment-policy |
| Q14 | What is the company's parental leave policy? | Out of scope: must decline | Any invented leave policy | (none) |
| Q15 | What is the dress code for warehouse staff? | Out of scope: must decline | Any invented dress code | (none) |

## What this set does and doesn't measure

- It measures **retrieval + grounded answering** on a small, clean corpus, including whether
  superseded versions leak into answers.
- It doesn't measure the PO tool (tool calling doesn't work on Spring AI 1.0.0-M1 with Ollama), long
  or messy documents, multi-turn conversations, or adversarial prompts.
- It's 15 questions from one author: a regression check, not a benchmark. Results are from a single
  run at temperature 0.
