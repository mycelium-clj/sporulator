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
