# Run 3 Observations (2026-03-18)

## Summary

Iterative graph construction (edge-by-edge reconciliation) dramatically improved schema convergence.
Reduced mismatches from 7 → 2 across 5 rounds. Remaining 2 are structurally impossible — they require
user inputs that can't flow from predecessor cells.

## Timeline

- 17:10 - Started with depth=0, auto-approve, 8 steps
- 17:11 - Decomposed into 8 steps: validate-and-reserve-inventory, apply-promotions, calculate-taxes, calculate-shipping, check-fraud-and-payment, calculate-loyalty, process-return, modify-order
- 17:12 - Graph walk completed (6 LLM edge decisions)
- 17:12-17:18 - Schema validation: 5 rounds of edge-by-edge fixes
  - Round 1: 7 mismatches → fixed
  - Round 2: 6 mismatches → fixed
  - Round 3: 4 mismatches → fixed
  - Round 4: 5 mismatches → fixed (oscillation from upstream propagation)
  - Round 5: 2 mismatches → could not fix
- 17:18 - Failed: `process-return → modify-order` missing `changes`, `order-input`

## Analysis

### What worked

1. **Edge-by-edge reconciliation converges** — The focused single-edge prompt lets DeepSeek fix one
   incompatibility at a time. Previously "LLM returned no corrections despite 7 mismatches" 5x;
   now it successfully resolves 5/7 edges.

2. **Pass-through context helps** — Showing all cell schemas in the edge fix prompt lets the LLM
   understand what downstream cells need and add pass-through fields.

3. **Level-by-level validation caught the root issue early** — Instead of recursing into 3 levels
   of promotion pipeline before finding the problem, it found the structural issue at depth 0 in ~7 minutes.

### What didn't work

The graph agent still chains 3 independent workflows (placement, return, modification) as sequential
steps. `modify-order` needs `changes` and `order-input` — these are new user inputs, not produced by
any cell in the placement pipeline.

The edge-by-edge fixer oscillated on this edge because:
- Adding `changes` to `process-return` output requires propagating it backward through the chain
- This creates new mismatches upstream (round 4: 4→5)
- The fields genuinely can't exist in the chain — they're external user inputs

### Comparison with Run 1/2

| Metric | Run 1 (batch, depth=3) | Run 2 (batch, depth=0) | Run 3 (edge-by-edge, depth=0) |
|--------|----------------------|----------------------|------------------------------|
| Time | 44 min | ~8 min | 8 min |
| Schema fix success | 0/7 | 0/7 | 5/7 |
| Root cause found | After 44 min | After 8 min | After 8 min (same) |
| Useful work | None | None | Resolved 5 structural mismatches |

## Root Cause (unchanged)

The spec models three separate operations (placement, return, modification) but the graph agent
designs them as one sequential pipeline. `process-return` and `modify-order` need user inputs
(`reason`, `returned-items`, `changes`, `order-input`) that don't exist in the placement flow.

## Next Steps

### Option 1: Run workflows separately
Run `:order/placement`, `:order/return`, `:order/modification` as 3 independent orchestration calls.
Each gets its own clean cell graph with no cross-workflow schema conflicts.

### Option 2: Detect structurally impossible mismatches
If a required field doesn't exist in the root input and isn't produced by any cell's output,
it can't be propagated. Detect this and report it as "structural: field X requires external input"
rather than attempting to fix it.

### Option 3: Support independent entry points in one graph
Allow the graph to have multiple `:start` points or separate subgraphs that share cells.
This matches the spec's actual structure: shared infrastructure (promotions, tax, shipping)
with independent entry points (placement, return, modification).
