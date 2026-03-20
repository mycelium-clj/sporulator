package agents

import (
	"strings"
	"testing"
)

func TestFixTierForAttempt(t *testing.T) {
	tests := []struct {
		attempt  int
		expected FixTier
	}{
		{0, FixTierStandard},  // edge case: zero
		{-1, FixTierStandard}, // edge case: negative
		{1, FixTierStandard},
		{2, FixTierNarrowed},
		{3, FixTierFresh},
		{4, FixTierFresh},
	}
	for _, tt := range tests {
		got := fixTierForAttempt(tt.attempt)
		if got != tt.expected {
			t.Errorf("attempt %d: expected tier %d, got %d", tt.attempt, tt.expected, got)
		}
	}
}

func TestExtractFirstFailingTest(t *testing.T) {
	tests := []struct {
		name     string
		output   string
		expected string
	}{
		{
			"single failure",
			`Testing example.test

FAIL in (test-basic-case) (test.clj:10)
expected: (= 42 (:result result))
  actual: (not (= 42 0))

Ran 3 tests containing 5 assertions.
1 failures, 0 errors.`,
			`FAIL in (test-basic-case) (test.clj:10)
expected: (= 42 (:result result))
  actual: (not (= 42 0))`,
		},
		{
			"multiple failures extracts first",
			`Testing example.test

FAIL in (test-first) (test.clj:10)
expected: 1
  actual: 0

FAIL in (test-second) (test.clj:20)
expected: 2
  actual: 0

Ran 2 tests containing 2 assertions.
2 failures, 0 errors.`,
			`FAIL in (test-first) (test.clj:10)
expected: 1
  actual: 0`,
		},
		{
			"error instead of failure",
			`Testing example.test

ERROR in (test-broken) (RT.java:1373)
expected: something
  actual: java.lang.NullPointerException

Ran 1 tests containing 1 assertions.
0 failures, 1 errors.`,
			`ERROR in (test-broken) (RT.java:1373)
expected: something
  actual: java.lang.NullPointerException`,
		},
		{
			"all pass",
			`Testing example.test

Ran 3 tests containing 5 assertions.
0 failures, 0 errors.`,
			"",
		},
		{
			"empty output",
			"",
			"",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := extractFirstFailingTest(tt.output)
			got = strings.TrimSpace(got)
			expected := strings.TrimSpace(tt.expected)
			if got != expected {
				t.Errorf("expected:\n%s\ngot:\n%s", expected, got)
			}
		})
	}
}

func TestGraduatedFixPromptTier1(t *testing.T) {
	prompt := buildGraduatedFixPrompt(FixPromptParams{
		TestOutput:   "FAIL in (test-x)",
		TestCode:     "(deftest test-x ...)",
		ImplCode:     "(fn [r d] {})",
		Brief:        CellBrief{ID: ":x/y", Doc: "Does Y", Schema: "{:input {} :output {}}"},
		CellID:       ":x/y",
		Attempt:      1,
		MaxAttempts:  5,
		GraphContext: "## Workflow Position\n...",
	})

	// Tier 1 should include code + brief + test output
	if !strings.Contains(prompt, "(fn [r d] {})") {
		t.Error("tier 1 should include impl code")
	}
	if !strings.Contains(prompt, "Does Y") {
		t.Error("tier 1 should include cell doc")
	}
	if !strings.Contains(prompt, "FAIL in (test-x)") {
		t.Error("tier 1 should include test output")
	}
}

func TestGraduatedFixPromptTier1WithGraphContext(t *testing.T) {
	graphCtx := "## Workflow Position\n\n**Receives data from:**\n- :order/validate"
	prompt := buildGraduatedFixPrompt(FixPromptParams{
		TestOutput:   "FAIL in (test-x)",
		TestCode:     "(deftest test-x ...)",
		ImplCode:     "(fn [r d] {})",
		Brief:        CellBrief{ID: ":x/y", Doc: "Does Y", Schema: "{:input {} :output {}}", Context: graphCtx},
		CellID:       ":x/y",
		Attempt:      1,
		MaxAttempts:  3,
		GraphContext: graphCtx,
	})

	// Standard tier includes graph context from the start
	if !strings.Contains(prompt, ":order/validate") {
		t.Error("standard tier should include graph context")
	}
	if !strings.Contains(prompt, "(fn [r d] {})") {
		t.Error("standard tier should include impl code")
	}
}

func TestGraduatedFixPromptTier2Narrowed(t *testing.T) {
	testOutput := `Testing example.test

FAIL in (test-first) (test.clj:10)
expected: 42
  actual: 0

FAIL in (test-second) (test.clj:20)
expected: 99
  actual: 0

Ran 2 tests.
2 failures.`

	prompt := buildGraduatedFixPrompt(FixPromptParams{
		TestOutput:  testOutput,
		TestCode:    "(deftest test-first ...)\n(deftest test-second ...)",
		ImplCode:    "(fn [r d] {})",
		Brief:       CellBrief{ID: ":x/y", Doc: "Does Y"},
		CellID:      ":x/y",
		Attempt:     2,
		MaxAttempts: 3,
	})

	// Tier 2 should focus on first failing test
	if !strings.Contains(prompt, "test-first") {
		t.Error("tier 2 should reference first failing test")
	}
	if !strings.Contains(prompt, "Focus on fixing this specific failure first") {
		t.Error("tier 2 should instruct to focus on specific failure")
	}
}

func TestGraduatedFixPromptTier4Fresh(t *testing.T) {
	prompt := buildGraduatedFixPrompt(FixPromptParams{
		TestOutput:  "FAIL in (test-x)",
		TestCode:    "(deftest test-x ...)",
		ImplCode:    "(fn [r d] {})",
		Brief:       CellBrief{ID: ":x/y", Doc: "Does Y", Schema: "{:input {} :output {}}"},
		CellID:      ":x/y",
		Attempt:     3,
		MaxAttempts: 3,
	})

	// Tier 4 (fresh) should instruct fresh implementation
	if !strings.Contains(prompt, "from scratch") {
		t.Error("tier fresh should instruct to implement from scratch")
	}
	// Should still include test code and brief
	if !strings.Contains(prompt, "(deftest test-x ...)") {
		t.Error("tier fresh should include test code")
	}
	if !strings.Contains(prompt, "Does Y") {
		t.Error("tier fresh should include cell doc")
	}
}

// TestAllTiersIncludeRequireInstruction verifies REQUIRE comment instruction is present in every tier.
func TestAllTiersIncludeRequireInstruction(t *testing.T) {
	base := FixPromptParams{
		TestOutput:  "FAIL in (test-x)\nexpected: 1\n  actual: 0\n\nRan 1 tests.\n1 failures.",
		TestCode:    "(deftest test-x ...)",
		ImplCode:    "(fn [r d] {})",
		Brief:       CellBrief{ID: ":x/y", Doc: "Does Y", Schema: "{}"},
		CellID:      ":x/y",
		MaxAttempts: 5,
	}

	for _, attempt := range []int{1, 2, 3} {
		p := base
		p.Attempt = attempt
		prompt := buildGraduatedFixPrompt(p)
		if !strings.Contains(prompt, ";; REQUIRE:") {
			t.Errorf("attempt %d: prompt should include REQUIRE comment instruction", attempt)
		}
	}
}

// TestAllTiersIncludeCriticalCellID verifies the CRITICAL cell ID line is present in every tier.
func TestAllTiersIncludeCriticalCellID(t *testing.T) {
	base := FixPromptParams{
		TestOutput:  "FAIL in (test-x)\nexpected: 1\n  actual: 0\n\nRan 1 tests.\n1 failures.",
		TestCode:    "(deftest test-x ...)",
		ImplCode:    "(fn [r d] {})",
		Brief:       CellBrief{ID: ":x/y", Doc: "Does Y", Schema: "{}"},
		CellID:      ":x/y",
		MaxAttempts: 5,
	}

	for _, attempt := range []int{1, 2, 3} {
		p := base
		p.Attempt = attempt
		prompt := buildGraduatedFixPrompt(p)
		if !strings.Contains(prompt, "CRITICAL: The cell ID is :x/y") {
			t.Errorf("attempt %d: prompt should include CRITICAL cell ID line", attempt)
		}
	}
}

// TestAllTiersIncludeDoNotIncludeNs verifies the ns exclusion instruction in every tier.
func TestAllTiersIncludeDoNotIncludeNs(t *testing.T) {
	base := FixPromptParams{
		TestOutput:  "FAIL in (test-x)\nexpected: 1\n  actual: 0\n\nRan 1 tests.\n1 failures.",
		TestCode:    "(deftest test-x ...)",
		ImplCode:    "(fn [r d] {})",
		Brief:       CellBrief{ID: ":x/y", Doc: "Does Y", Schema: "{}"},
		CellID:      ":x/y",
		MaxAttempts: 5,
	}

	for _, attempt := range []int{1, 2, 3} {
		p := base
		p.Attempt = attempt
		prompt := buildGraduatedFixPrompt(p)
		if !strings.Contains(prompt, "Do NOT include (ns") {
			t.Errorf("attempt %d: prompt should include ns exclusion instruction", attempt)
		}
	}
}

func TestNarrowedFallbackToExpanded(t *testing.T) {
	// When test output has no FAIL/ERROR lines, narrowed should fallback to expanded
	prompt := buildGraduatedFixPrompt(FixPromptParams{
		TestOutput:   "Compilation error: cannot find symbol",
		TestCode:     "(deftest test-x ...)",
		ImplCode:     "(fn [r d] {})",
		Brief:        CellBrief{ID: ":x/y", Doc: "Does Y", Schema: "{}"},
		CellID:       ":x/y",
		Attempt:      2,
		MaxAttempts:  3,
		GraphContext: "## Workflow Position\n...",
	})

	// When no FAIL pattern found, narrowed falls back to standard prompt
	if strings.Contains(prompt, "Focus on fixing this specific failure first") {
		t.Error("narrowed fallback should not contain narrowed instructions")
	}
}

func TestNarrowedIncludesGraphContext(t *testing.T) {
	testOutput := "FAIL in (test-x) (test.clj:10)\nexpected: 1\n  actual: 0\n\nRan 1 tests.\n1 failures."
	graphCtx := "## Workflow Position\n\n**Receives data from:**\n- :order/validate"
	prompt := buildGraduatedFixPrompt(FixPromptParams{
		TestOutput:   testOutput,
		TestCode:     "(deftest test-x ...)",
		ImplCode:     "(fn [r d] {})",
		Brief:        CellBrief{ID: ":x/y", Doc: "Does Y", Schema: "{}"},
		CellID:       ":x/y",
		Attempt:      2,
		MaxAttempts:  3,
		GraphContext: graphCtx,
	})

	if !strings.Contains(prompt, ":order/validate") {
		t.Error("narrowed tier should include graph context")
	}
}

func TestStrongerAttemptInfo(t *testing.T) {
	prompt := buildFixPrompt(FixPromptParams{
		TestOutput:  "FAIL",
		TestCode:    "(deftest t ...)",
		ImplCode:    "(fn [r d] {})",
		Brief:       CellBrief{ID: ":x/y", Doc: "test"},
		CellID:      ":x/y",
		Attempt:     3,
		MaxAttempts: 5,
	})

	if !strings.Contains(prompt, "attempt 3/5") {
		t.Error("fix prompt should include 'attempt N/M' format")
	}
}
