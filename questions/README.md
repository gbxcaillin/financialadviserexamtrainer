# Question bank

The master bank is `FASEA_Question_Bank_v12.xlsx`. **It has not been committed yet** — add it to
this folder. The app does not read the spreadsheet at runtime: the questions are embedded as
JSON in `shared/index.html` (the `EMBEDDED_QUESTIONS` script block), taken from ExamDesktop
`feature/v2-question-bank` at commit `393a352`.

## Embedded JSON format

Compact keys, expanded on load by the normaliser just above `let ALL_QUESTIONS`:

| Key | Meaning |
|---|---|
| `s` | section |
| `q` | question text |
| `o` | options array (4 for MCQ, `["True","False"]` for True/False) |
| `c` | correct option index (0–3) |
| `e` | explanation, one line per option, prefixed `✓` (correct) or `✗` / `X` (incorrect) |
| `t` | pool/type: `TrueFalse`, `Values`, `SecondaryValues`, `Applied`, `Scenario`, `Complex`; omitted for standard MCQ |
| `sk` | scenario key, e.g. `S1 – Melissa Kumar (Aspen Private Wealth)` (Scenario only) |
| `sc` | scenario narrative (Scenario only) |

## What is embedded (1,062 questions)

| Pool (`t`) | Section (`s`) | Count |
|---|---|---|
| Standard | Client Scenarios | 302 |
| Standard | Behavioural Finance | 40 |
| Standard | Ethics & Corps Act | 18 |
| Standard | Privacy & AML/CTF | 9 |
| Standard | SOA/FDS/FSG Obligations | 8 |
| Standard | RG 104 – Licensee Obligations | 5 |
| Standard | Conflicted Remuneration | 5 |
| Standard | Corporations Act | 4 |
| Standard | Mixed – Advanced | 3 |
| Standard | Scaled Advice | 2 |
| Standard | General vs Personal Advice | 1 |
| Applied | Applied Values | 23 |
| Applied | Investment Concepts | 10 |
| Complex | Complex Questions | 55 |
| Scenario | Scenario (31 scenarios) | 134 |
| SecondaryValues | Code Values | 179 |
| Values | Code Values | 125 |
| Values | RG 105 – Organisational Competence | 5 |
| TrueFalse | 13 sections (Corporations Act 35, Ethics 33, Privacy 15, …) | 134 |

**Known issue:** the handoff document lists 19 standard sections with only 3 "Client Scenarios"
questions, but 302 of the 397 embedded standard questions carry the `Client Scenarios` section.
"Practice by Section" matches on section names, so those questions currently appear only in
mixed practice and timed exams. Re-export the Standard tab with the intended section labels to fix.

## Free tier

The free tier is the first 50 questions taken round-robin across sections, excluding Scenario
and Complex questions (`buildFreePool()` in `shared/index.html`). Reordering or relabelling the
bank changes which questions are free.

## Updating the bank

1. Export the tabs to the compact JSON format above.
2. Replace the array in the `EMBEDDED_QUESTIONS` block of `shared/index.html`.
3. Run `scripts/sync-web.sh` and `npm test`.
