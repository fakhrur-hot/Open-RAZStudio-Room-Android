---
name: story-trace
description: >-
  Progressive cause-and-effect investigation for StudioRoom. Starts at a tiny
  budget, identifies one symbol, and stops when confidence is at least 0.8.
  Escalates hop by hop only when evidence is weak. Use on every search,
  research, check, why, or find, and whenever an agent is stuck, debugging a
  failure, or asking what breaks. Never answer from search hits alone.
---

# StoryTrace

On-demand micrograph. Default budget is tiny. Stop when confidence ≥ 0.8. Escalate one step only when the rule below says so. Do not build a project graph.

Anti-RAG: never answer from search hits alone. A file path is not evidence. Every conclusion is Node → Edge → Story. No purpose without at least two edges at confidence ≥ 0.7. The narrator ignores every edge below 0.7.

## Budget

| Budget | Cap | Opens |
|---|---|---|
| tiny | 3 nodes, 6 edges | Symbol name only. No bodies. |
| small | 10 nodes, 20 edges | Direct callers and callees. Still no bodies. |
| medium | 30 nodes, 50 edges | Two hops, or ≤80 lines that prove a weak edge. |
| full | One module | Module scan. Project-wide only under escalation `< 0.2`. |

Skip `third_party/`, `build/`, `.gradle`, and generated sources at every budget.

## Ceiling

The question sets the highest stage. Confidence decides whether you climb. Do not run stages above the ceiling.

| User ask | Ceiling | Stop early when |
|---|---|---|
| find, search, where is | 1 Identify | Symbol + module, confidence ≥ 0.8 |
| check, who calls | 2 Neighborhood | Direct `calls` and `called_by` exist |
| why, purpose, how | 3 Purpose, then story only if purpose is still thin | ≥2 strong edges and a purpose |
| impact, remove, refactor, safe to delete | 4 Impact | Right walk states what breaks |
| bug, wrong, black, slow, stuck on a failure | 5 Root cause | First divergence found |
| pixels, color, preview vs export, what data moves | 3 + data flow | Input and output named |

Pick one traverser. Do not run the others.

## Escalation

Run the next row only if confidence is still below the threshold after the current stage.

| Confidence | Do next | Budget |
|---|---|---|
| ≥ 0.8 | Stop. Emit the card for this stage. | unchanged |
| < 0.8 | One hop left and one hop right. Symbols only. | small |
| < 0.6 | Open the two or three lines that prove the weakest edge. | medium |
| < 0.4 | Scan that one module. | full |
| < 0.2 | Wider search, still skipping `third_party/` and build output. | full |

## Stage 0 — Intent resolver

Always first. Cost: zero file bodies. Output:

```yaml
symbol:
module:
related_symbol:
confidence:
budget: tiny
status: target_found
```

Resolve from the question and this table before any search. A product name (`StudioRoom`, the app) is not a node: cap confidence at 0.4 and pick the subsystem below that the words match. If nothing matches, grep the exact symbol once with a tight head limit, record `name`, `module`, `type`, and stop if the ceiling is Identify.

| Question words | symbol | related_symbol | module | confidence |
|---|---|---|---|---|
| preview vs export, color differs, parity | `gles_renderer.cpp` | `apply_macro.cpp` | `lib/raw-native` `v3/` | 0.81 |
| decode, demosaic, stage A | `stage_a.cpp` | | `lib/raw-native` `v3/` | 0.80 |
| coordinator, editor actions | `RawV3Coordinator` | `RawV3Engine` | `feature/photo-editor` `raw_v3/` | 0.85 |
| shader slot, params | `ShaderParams` | | `feature/photo-editor` `raw_v3/` | 0.85 |
| mask | mask tab / mask native | | `feature/photo-editor` | 0.70 |
| batch | `RawBatchPrefs` | | `core/settings` | 0.80 |
| tether, Canon, PTP | canon-sync entry | | `feature/canon-sync` | 0.75 |
| lens, profile | `lensfun_android.cpp` | | `lib/raw-native` | 0.75 |

Parity is a known edge: `gles_renderer.cpp` `PARITY` `apply_macro.cpp` at 0.81. Do not read both files to restate it.

Spines if you must place a hop: UI `presentation/raw/` → `raw_v3/`; JNI `v3_jni.cpp`; navigation `feature/root` `ChildProvider`; Room `core/database`; video keyframes `feature/video-editor`.

## Stage 1 — Symbol discovery

Grep the symbol. Keep the matching line, not the file. Fill `module` and `type` (`class`, `function`, `native`, `pref`, `dao`). Status `target_found`. If ceiling is 1 and confidence ≥ 0.8, stop. No story.

## Stage 2 — Local graph

Symbols only. Depth 1.

```yaml
calls: []
called_by: []
depth: 1
```

Each edge:

```yaml
edge:
  source:
  target:
  relation: CALLS
  confidence:
```

Relations: `CALLS`, `INJECTS`, `READS`, `WRITES`, `JNI`, `PARITY`, `DEPENDS_ON`, `NAVIGATES`, `MAY_CALL`.

Score the edge from the line you saw:

| Seen on the line | confidence |
|---|---|
| Call, `@Inject` parameter, JNI name, Room query | 1.0 |
| Known parity pair from the table above | 0.81 |
| Same-module symbol mention, no call syntax | 0.56 `MAY_CALL` |
| Comment or filename only | 0.3, not evidence |

## Stage 3 — Purpose

Infer only when `called_by` ≥ 1 and `calls` ≥ 1, with ≥2 edges at ≥ 0.7.

```yaml
purpose:
evidence:
  - edge_1
  - edge_2
```

Otherwise `purpose: unknown` and escalate one step, or stop if the budget cap is already hit. Do not guess a purpose to fill the blank.

## Stage 4 — One traverser

Stay inside the budget cap.

- Purpose: left walk, at most 2 hops, why it exists.
- Impact: right walk only. `impact_if_removed` must name a broken feature.
- Origin: left walk to the user action or file input.
- Endpoint: right walk to the screen, file, or row.
- Data flow: `input` and `output`, not callers. Pixel questions use RAW file → Stage A → demosaic → ShaderParams → GL preview → export macro → JPEG, and only the hops this node touches.
- Cost: one resource. Room or Stage A cache → disk. PTP → network and disk. GLES or bitmap → memory. Models → memory and storage. Gesture full-bitmap render → memory.
- Root cause, only for a failure: start at the broken endpoint, walk left, stop at the first node whose input edge is missing or whose parity partner was not found. Do not continue past that suspect.

```yaml
suspect:
reason:
confidence:
```

## Stage 5 — Business value

Run only after stage 4. One line each, tied to an edge already in the card.

```yaml
business_domain:
business_value:
  technical:
  business:
  loss_if_removed:
```

Domain tags: preview and export, RAW decode, camera and lens profile, masks, tethering, batch, export metadata, looks, wiring, local storage, navigation, edition. In user-facing text say "camera and lens profile", not library names.

## Stage 6 — Narrator

Run only when the ceiling is purpose, impact, or root cause, and only after the card is filled. One short paragraph. It explains the card. It does not search. It drops edges below 0.7.

## Cards

Identify stop:

```yaml
symbol:
module:
type:
confidence:
status: target_found
```

Neighborhood or purpose stop:

```yaml
node:
  name:
  what:
  why:
calls: []
called_by: []
purpose:
evidence: []
confidence:
status: stop
```

Story, impact, data flow, or root cause:

```yaml
node:
  name:
  what:
  why:
origin_walk: []
effect_walk: []
data_flow:
  input:
  output:
business_domain:
business_value:
  technical:
  business:
  loss_if_removed:
impact_if_removed:
evidence: []
confidence:
suspect:
```

Narrative goes after that card, never before it, and never on an identify stop.
