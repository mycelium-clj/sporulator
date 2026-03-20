package agents

import (
	"fmt"
	"regexp"
	"strings"
)

// FixTier represents the escalation level of a fix attempt.
type FixTier int

const (
	FixTierStandard FixTier = iota // attempt 1: code + brief + test output + graph context
	FixTierNarrowed                // attempt 2: focuses on first failing test
	FixTierFresh                   // attempt 3: fresh start, implement from scratch
)

// fixTierForAttempt returns the escalation tier for a given attempt number.
// With 3 max attempts, escalation is aggressive: standard → narrowed → fresh.
func fixTierForAttempt(attempt int) FixTier {
	switch {
	case attempt <= 1:
		return FixTierStandard
	case attempt == 2:
		return FixTierNarrowed
	default:
		return FixTierFresh
	}
}

// failPattern matches FAIL or ERROR lines in clojure.test output.
var failPattern = regexp.MustCompile(`(?m)^(FAIL|ERROR) in \(`)

// extractFirstFailingTest extracts the first FAIL/ERROR block from clojure.test output.
// Returns empty string if no failures found.
func extractFirstFailingTest(output string) string {
	locs := failPattern.FindAllStringIndex(output, 2)
	if len(locs) == 0 {
		return ""
	}

	start := locs[0][0]
	var end int
	if len(locs) >= 2 {
		// End at the start of the second failure
		end = locs[1][0]
	} else {
		// End at "Ran N tests" line or end of string
		ranIdx := strings.Index(output[start:], "\nRan ")
		if ranIdx >= 0 {
			end = start + ranIdx
		} else {
			end = len(output)
		}
	}

	return strings.TrimSpace(output[start:end])
}

// buildGraduatedFixPrompt constructs a fix prompt with escalating context per attempt tier.
func buildGraduatedFixPrompt(p FixPromptParams) string {
	tier := fixTierForAttempt(p.Attempt)

	switch tier {
	case FixTierNarrowed:
		return buildNarrowedFixPrompt(p)
	case FixTierFresh:
		return buildFreshFixPrompt(p)
	default:
		// Standard tier: includes graph context from the start
		return buildFixPrompt(p)
	}
}

// buildExpandedFixPrompt adds graph context on top of the standard prompt.
func buildExpandedFixPrompt(p FixPromptParams) string {
	var extra strings.Builder

	if p.GraphContext != "" {
		extra.WriteString("\n")
		extra.WriteString(p.GraphContext)
	}
	if p.Brief.Context != "" && p.Brief.Context != p.GraphContext {
		extra.WriteString("\n")
		extra.WriteString(p.Brief.Context)
	}

	return fmt.Sprintf(`The tests are STILL failing (attempt %d/%d). Previous fixes didn't work.

## Cell Contract
- **Cell ID:** %s
- **Implementation Requirements:** %s
- **Schema:** %s
%s
## Current Implementation
`+"```clojure\n%s\n```"+`

## Test Output
`+"```\n%s\n```"+`

## Test Code
`+"```clojure\n%s\n```"+`

%s

Carefully re-read the cell's purpose, schema, and workflow position above.
Fix the implementation. Return ONLY:
1. (OPTIONAL) Helper functions
2. (REQUIRED) (fn [resources data] ...) — MUST be the LAST form

If you need extra requires beyond [mycelium.cell :as cell], list each as a comment:
;; REQUIRE: [clojure.string :as str]

Do NOT include (ns ...) or (cell/defcell ...) — those are generated for you.
CRITICAL: The cell ID is %s.`,
		p.Attempt, p.MaxAttempts,
		p.CellID, p.Brief.Doc, p.Brief.Schema,
		extra.String(),
		p.ImplCode,
		p.TestOutput,
		p.TestCode,
		mathPrecisionRules,
		p.CellID)
}

// buildNarrowedFixPrompt focuses on the first failing test to reduce cognitive load.
func buildNarrowedFixPrompt(p FixPromptParams) string {
	firstFailure := extractFirstFailingTest(p.TestOutput)
	if firstFailure == "" {
		// Fallback to expanded if can't extract
		return buildExpandedFixPrompt(p)
	}

	var extra strings.Builder
	if p.GraphContext != "" {
		extra.WriteString("\n")
		extra.WriteString(p.GraphContext)
	}
	if p.Brief.Context != "" && p.Brief.Context != p.GraphContext {
		extra.WriteString("\n")
		extra.WriteString(p.Brief.Context)
	}

	return fmt.Sprintf(`The tests are STILL failing after %d attempts. Focus on fixing this specific failure first:

## First Failing Test
`+"```\n%s\n```"+`

## Cell Contract
- **Cell ID:** %s
- **Implementation Requirements:** %s
- **Schema:** %s
%s
## Current Implementation
`+"```clojure\n%s\n```"+`

## Full Test Code
`+"```clojure\n%s\n```"+`

%s

Focus on fixing this specific failure first. Trace through the logic step-by-step:
1. What input does this test provide?
2. What does your current code do with that input?
3. Where does the actual output diverge from expected?

Return ONLY:
1. (OPTIONAL) Helper functions
2. (REQUIRED) (fn [resources data] ...) — MUST be the LAST form

If you need extra requires beyond [mycelium.cell :as cell], list each as a comment:
;; REQUIRE: [clojure.string :as str]

Do NOT include (ns ...) or (cell/defcell ...) — those are generated for you.
CRITICAL: The cell ID is %s.`,
		p.Attempt,
		firstFailure,
		p.CellID, p.Brief.Doc, p.Brief.Schema,
		extra.String(),
		p.ImplCode,
		p.TestCode,
		mathPrecisionRules,
		p.CellID)
}

// buildFreshFixPrompt instructs the LLM to start from scratch.
func buildFreshFixPrompt(p FixPromptParams) string {
	var extra strings.Builder
	if p.GraphContext != "" {
		extra.WriteString("\n")
		extra.WriteString(p.GraphContext)
	}
	if p.Brief.Context != "" && p.Brief.Context != p.GraphContext {
		extra.WriteString("\n")
		extra.WriteString(p.Brief.Context)
	}

	return fmt.Sprintf(`After %d failed attempts, implement this cell from scratch. Discard your previous approach entirely.

## Cell Contract
- **Cell ID:** %s
- **Implementation Requirements:** %s
- **Schema:** %s
%s
## Tests That Must Pass
`+"```clojure\n%s\n```"+`

## Most Recent Test Output (showing what went wrong)
`+"```\n%s\n```"+`

%s

Implement the cell from scratch. Think step by step:
1. Read each test carefully to understand the expected behavior.
2. Design a clean implementation that satisfies ALL tests.
3. Pay close attention to data types, rounding, and edge cases.

Return ONLY:
1. (OPTIONAL) Helper functions
2. (REQUIRED) (fn [resources data] ...) — MUST be the LAST form

If you need extra requires beyond [mycelium.cell :as cell], list each as a comment:
;; REQUIRE: [clojure.string :as str]

Do NOT include (ns ...) or (cell/defcell ...) — those are generated for you.
CRITICAL: The cell ID is %s.`,
		p.Attempt,
		p.CellID, p.Brief.Doc, p.Brief.Schema,
		extra.String(),
		p.TestCode,
		p.TestOutput,
		mathPrecisionRules,
		p.CellID)
}


// FixPromptParams holds all context needed to build a fix prompt.
type FixPromptParams struct {
	TestOutput   string    // clojure.test output showing failures
	TestCode     string    // the locked test code
	ImplCode     string    // current implementation code
	Brief        CellBrief // cell doc, schema, requires
	CellID       string
	Attempt      int
	MaxAttempts  int
	GraphContext string // predecessor/successor info (Phase 2)
}

// buildFixPrompt constructs the prompt sent to the LLM when tests fail.
// Includes the current implementation, cell brief, test output, and test code
// so the LLM has full context for the fix.
func buildFixPrompt(p FixPromptParams) string {
	var extra strings.Builder

	// Include graph context if available (from Phase 2)
	if p.GraphContext != "" {
		extra.WriteString("\n")
		extra.WriteString(p.GraphContext)
	}
	// Include brief context if available
	if p.Brief.Context != "" && p.Brief.Context != p.GraphContext {
		extra.WriteString("\n")
		extra.WriteString(p.Brief.Context)
	}

	return fmt.Sprintf(`The tests are failing (attempt %d/%d).

## Cell Contract
- **Cell ID:** %s
- **Implementation Requirements:** %s
- **Schema:** %s
%s
## Current Implementation
`+"```clojure\n%s\n```"+`

## Test Output
`+"```\n%s\n```"+`

## Test Code
`+"```clojure\n%s\n```"+`

%s

Fix the implementation. Return ONLY:
1. (OPTIONAL) Helper functions — define any helper functions you need
2. (REQUIRED) (fn [resources data] ...) — MUST be the LAST form

If you need extra requires beyond [mycelium.cell :as cell], list each as a comment:
;; REQUIRE: [clojure.string :as str]

Do NOT include (ns ...) or (cell/defcell ...) — those are generated for you.
CRITICAL: The cell ID is %s.`,
		p.Attempt, p.MaxAttempts,
		p.CellID, p.Brief.Doc, p.Brief.Schema,
		extra.String(),
		p.ImplCode,
		p.TestOutput,
		p.TestCode,
		mathPrecisionRules,
		p.CellID)
}
