# Kill-Switch Register

Measurable conditions that **halt the planning loop and force an operator
kill/pivot decision**. {{BUNDLE_NAME}} bundle, ratified {{DATE}}. Checked at
every P0 viability review and whenever new data lands on any input. Tripping
a switch never auto-kills — it halts work and escalates; only the operator
kills or pivots.

Reference bundles (pick at bootstrap; thresholds become rows below; delete
this block once the rows are instantiated):
- **Conservative:** no paying customer by month 4 post-validation-gate;
  cumulative cash > $2,500; sustained > 15 hrs/wk for 4+ weeks; any
  compliance red flag hard-stops.
- **Moderate:** month 6; $5,000; 8+ weeks; compliance hard-stops.
- **Patient:** month 9; $10,000; hours flexible; compliance + two
  consecutive failed viability reviews hard-stop.

| ID | Condition | Threshold | Clock starts | Current status |
|----|-----------|-----------|--------------|----------------|
| KS-1 | Customer traction | No paying customer within {{N}} months of Gate G1 | G1 pass date | Not started |
| KS-2 | Cash burn | Cumulative project cash > {{$X}} | Already running | ~$0 |
| KS-3 | Operator hours | > {{N}} hrs/wk sustained {{M}}+ consecutive weeks | Already running | Within budget |
| KS-4 | Compliance red flag | {{DOMAIN_RED_FLAG — e.g. counsel says the model is unlawful on all paths; regulator contact}} | Always armed | {{status}} |
| KS-5 | Early-funnel signal | {{CHEAP_EARLY_METRIC — e.g. <1 conversion from first N field conversations}} | First field contact | Not started |
| KS-6 | Product-ceiling collapse | {{THE_FATAL_TECHNICAL_FACT — the single capability without which the product is hollow}} | Capability verification | {{status}} |
| KS-7 | Viability verdict | A P0 adversarial viability review returns **kill** | Each review | {{status}} |

Rules:
- Status column updated with evidence + date at every viability review;
  stale (>45 days) status is itself an escalation.
- Thresholds change only by operator edit or ADR — never by a working doc.
- Near-miss (within 20% of a threshold) → flagged in the next digest.
