package agents

import (
	"regexp"
	"strings"
)

var codeBlockRe = regexp.MustCompile("(?s)`{3,}(?:clojure|clj|edn)?\\s*\n(.*?)\n\\s*`{3,}")

// ExtractCodeBlocks returns all fenced code blocks from a response.
// Matches ```clojure, ```clj, ```edn, and bare ``` blocks.
func ExtractCodeBlocks(response string) []string {
	matches := codeBlockRe.FindAllStringSubmatch(response, -1)
	var blocks []string
	for _, match := range matches {
		block := strings.TrimSpace(match[1])
		if block != "" {
			blocks = append(blocks, block)
		}
	}
	return blocks
}

// ExtractFirstCodeBlock returns the first fenced code block from a response.
// Falls back to stripFenceMarkers only if the result looks like Clojure code.
// Returns empty string if no valid code block is found.
func ExtractFirstCodeBlock(response string) string {
	blocks := ExtractCodeBlocks(response)
	if len(blocks) > 0 {
		return blocks[0]
	}
	// Fallback: try stripping fence markers (handles malformed fences)
	stripped := stripFenceMarkers(strings.TrimSpace(response))
	if looksLikeClojure(stripped) {
		return stripped
	}
	// Last resort: scan for (ns ...) or (cell/defcell ...) form in raw text
	if idx := strings.Index(response, "(ns "); idx >= 0 {
		return balanceParens(response[idx:])
	}
	return ""
}

// looksLikeClojure checks if text starts with a Clojure form.
func looksLikeClojure(s string) bool {
	trimmed := strings.TrimSpace(s)
	return strings.HasPrefix(trimmed, "(ns ") ||
		strings.HasPrefix(trimmed, "(def") ||
		strings.HasPrefix(trimmed, "(cell/") ||
		strings.HasPrefix(trimmed, "(require")
}

// parenDepth returns the unclosed paren depth of Clojure code (0 = balanced).
func parenDepth(s string) int {
	depth := 0
	inString := false
	escaped := false
	for _, ch := range s {
		if escaped {
			escaped = false
			continue
		}
		if ch == '\\' && inString {
			escaped = true
			continue
		}
		if ch == '"' {
			inString = !inString
			continue
		}
		if inString {
			continue
		}
		if ch == '(' {
			depth++
		} else if ch == ')' {
			depth--
		}
	}
	return depth
}

// IsTruncated returns true if Clojure code appears to be truncated (unbalanced parens).
func IsTruncated(code string) bool {
	return parenDepth(code) > 0
}

// balanceParens appends closing parens to truncated Clojure code.
func balanceParens(s string) string {
	depth := 0
	inString := false
	escaped := false
	for _, ch := range s {
		if escaped {
			escaped = false
			continue
		}
		if ch == '\\' && inString {
			escaped = true
			continue
		}
		if ch == '"' {
			inString = !inString
			continue
		}
		if inString {
			continue
		}
		if ch == '(' {
			depth++
		} else if ch == ')' {
			depth--
		}
	}
	if depth > 0 {
		return s + strings.Repeat(")", depth)
	}
	return s
}

// stripFenceMarkers removes markdown code fence markers from the start and end.
func stripFenceMarkers(s string) string {
	lines := strings.Split(s, "\n")
	if len(lines) < 2 {
		return s
	}
	first := strings.TrimSpace(lines[0])
	if strings.HasPrefix(first, "```") {
		lines = lines[1:]
	}
	if len(lines) > 0 {
		last := strings.TrimSpace(lines[len(lines)-1])
		if strings.HasPrefix(last, "```") {
			lines = lines[:len(lines)-1]
		}
	}
	return strings.Join(lines, "\n")
}

// ExtractDefcell extracts the first (cell/defcell ...) form from a response.
// Looks inside code blocks first, then falls back to raw text.
func ExtractDefcell(response string) string {
	// Try code blocks first
	for _, block := range ExtractCodeBlocks(response) {
		if idx := strings.Index(block, "(cell/defcell"); idx >= 0 {
			return extractBalancedForm(block[idx:])
		}
	}
	// Fall back to raw text
	if idx := strings.Index(response, "(cell/defcell"); idx >= 0 {
		return extractBalancedForm(response[idx:])
	}
	return ExtractFirstCodeBlock(response)
}

// ExtractAllDefcells extracts all (cell/defcell ...) forms from a response.
func ExtractAllDefcells(response string) []string {
	source := response
	// Prefer code blocks
	blocks := ExtractCodeBlocks(response)
	if len(blocks) > 0 {
		source = strings.Join(blocks, "\n\n")
	}

	var forms []string
	remaining := source
	for {
		idx := strings.Index(remaining, "(cell/defcell")
		if idx < 0 {
			break
		}
		form := extractBalancedForm(remaining[idx:])
		if form != "" {
			forms = append(forms, form)
		}
		remaining = remaining[idx+len(form):]
	}
	return forms
}

// extractBalancedForm extracts a balanced parenthesized form starting at s[0]='('.
func extractBalancedForm(s string) string {
	if len(s) == 0 || s[0] != '(' {
		return ""
	}
	depth := 0
	inString := false
	escaped := false
	for i, ch := range s {
		if escaped {
			escaped = false
			continue
		}
		if ch == '\\' && inString {
			escaped = true
			continue
		}
		if ch == '"' {
			inString = !inString
			continue
		}
		if inString {
			continue
		}
		if ch == '(' {
			depth++
		} else if ch == ')' {
			depth--
			if depth == 0 {
				return s[:i+1]
			}
		}
	}
	return s // unbalanced — return what we have
}
