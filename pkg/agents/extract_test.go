package agents

import (
	"testing"
)

func TestExtractCodeBlocks_FourBackticks(t *testing.T) {
	input := "````clojure\n(ns my.ns)\n````\n"
	blocks := ExtractCodeBlocks(input)
	if len(blocks) != 1 {
		t.Fatalf("expected 1 block, got %d", len(blocks))
	}
	if blocks[0] != "(ns my.ns)" {
		t.Errorf("expected '(ns my.ns)', got %q", blocks[0])
	}
}

func TestExtractCodeBlocks_MultipleBlocks(t *testing.T) {
	input := "First:\n```clojure\n(def a 1)\n```\nSecond:\n```edn\n{:key :val}\n```\n"
	blocks := ExtractCodeBlocks(input)
	if len(blocks) != 2 {
		t.Fatalf("expected 2 blocks, got %d", len(blocks))
	}
	if blocks[0] != "(def a 1)" {
		t.Errorf("block 0: expected '(def a 1)', got %q", blocks[0])
	}
	if blocks[1] != "{:key :val}" {
		t.Errorf("block 1: expected '{:key :val}', got %q", blocks[1])
	}
}

func TestStripFenceMarkers(t *testing.T) {
	tests := []struct {
		name, input, expected string
	}{
		{
			"leading fence",
			"```clojure\n(ns my.ns)\n(def x 1)",
			"(ns my.ns)\n(def x 1)",
		},
		{
			"both fences",
			"```clojure\n(ns my.ns)\n```",
			"(ns my.ns)",
		},
		{
			"four backticks",
			"````clojure\n(ns my.ns)\n````",
			"(ns my.ns)",
		},
		{
			"no fences",
			"(ns my.ns)",
			"(ns my.ns)",
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := stripFenceMarkers(tt.input)
			if result != tt.expected {
				t.Errorf("expected %q, got %q", tt.expected, result)
			}
		})
	}
}

func TestExtractFirstCodeBlock_NoCodeBlock_ReturnsEmpty(t *testing.T) {
	// When LLM returns just explanation text with no code blocks
	input := "We are given that there is an EOF while reading error.\nLet's count the parentheses..."
	result := ExtractFirstCodeBlock(input)
	if result != "" {
		t.Errorf("expected empty string for non-code response, got %q", result)
	}
}

func TestExtractFirstCodeBlock_FallbackToNsForm(t *testing.T) {
	// When LLM returns code without proper fences but with (ns ...) form
	input := "Here is the fix:\n(ns my.ns\n  (:require [mycelium.cell :as cell]))\n\n(cell/defcell :my/cell {} (fn [_ d] d))"
	result := ExtractFirstCodeBlock(input)
	if result == "" {
		t.Fatal("expected non-empty result")
	}
	if result[:4] != "(ns " {
		t.Errorf("expected result to start with '(ns ', got %q", result[:20])
	}
}

func TestBalanceParens(t *testing.T) {
	tests := []struct {
		name     string
		input    string
		wantDiff int // expected parens to add
	}{
		{"balanced", "(defn foo [] (+ 1 2))", 0},
		{"missing_one", "(defn foo [] (+ 1 2)", 1},
		{"missing_two", "(defn foo [] (+ 1 2", 2},
		{"with_strings", `(defn foo [] (str "hello (world"))`, 0},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := balanceParens(tt.input)
			if tt.wantDiff == 0 {
				if result != tt.input {
					t.Errorf("balanced input should be unchanged, got %q", result)
				}
			} else {
				added := len(result) - len(tt.input)
				if added != tt.wantDiff {
					t.Errorf("expected %d parens added, got %d", tt.wantDiff, added)
				}
			}
		})
	}
}

func TestLooksLikeClojure(t *testing.T) {
	if looksLikeClojure("We are given that...") {
		t.Error("explanation text should not look like Clojure")
	}
	if !looksLikeClojure("(ns my.ns)") {
		t.Error("ns form should look like Clojure")
	}
	if !looksLikeClojure("(defn foo [] 1)") {
		t.Error("defn form should look like Clojure")
	}
	if !looksLikeClojure("  (cell/defcell :my/cell {} (fn [_ d] d))") {
		t.Error("cell/defcell form should look like Clojure")
	}
}

func TestExtractCodeBlocks_MultilineContent(t *testing.T) {
	input := "```clojure\n(ns my.ns\n  (:require [foo.bar]))\n\n(defn hello []\n  \"world\")\n```"
	blocks := ExtractCodeBlocks(input)
	if len(blocks) != 1 {
		t.Fatalf("expected 1 block, got %d", len(blocks))
	}
	expected := "(ns my.ns\n  (:require [foo.bar]))\n\n(defn hello []\n  \"world\")"
	if blocks[0] != expected {
		t.Errorf("expected %q, got %q", expected, blocks[0])
	}
}
