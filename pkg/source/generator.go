// Package source generates Clojure source files from cells and manifests
// stored in the sporulator database, for production deployment.
package source

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/mycelium-clj/sporulator/pkg/store"
)

// Config configures source file generation.
type Config struct {
	// OutputDir is the root directory for generated source files.
	OutputDir string
	// BaseNamespace is the Clojure namespace prefix (e.g. "myapp").
	BaseNamespace string
}

// GenerateResult describes the files generated.
type GenerateResult struct {
	Files []GeneratedFile `json:"files"`
}

// GeneratedFile describes a single generated file.
type GeneratedFile struct {
	Path      string   `json:"path"`                // relative to OutputDir
	Namespace string   `json:"namespace"`            // Clojure namespace
	CellIDs   []string `json:"cell_ids,omitempty"`   // cell IDs in this file (empty for manifests)
}

// Generate reads all latest cells and manifests from the store and writes
// Clojure source files to the configured output directory.
func Generate(st *store.Store, cfg Config) (*GenerateResult, error) {
	cells, err := st.ListCells()
	if err != nil {
		return nil, fmt.Errorf("list cells: %w", err)
	}

	// Group cells by namespace prefix (e.g. :order/foo -> order)
	groups := groupCellsByPrefix(cells)

	var result GenerateResult

	// Sort prefixes for deterministic output order
	sortedPrefixes := make([]string, 0, len(groups))
	for prefix := range groups {
		sortedPrefixes = append(sortedPrefixes, prefix)
	}
	sort.Strings(sortedPrefixes)

	for _, prefix := range sortedPrefixes {
		cellIDs := groups[prefix]
		ns := cfg.BaseNamespace + ".cells." + prefix
		relPath := nsToPath(ns)

		// Load full cell records
		var handlers []string
		for _, id := range cellIDs {
			cell, err := st.GetLatestCell(id)
			if err != nil {
				return nil, fmt.Errorf("load cell %s: %w", id, err)
			}
			if cell == nil {
				continue
			}
			handlers = append(handlers, cell.Handler)
		}

		content := buildCellNamespace(ns, handlers)

		fullPath := filepath.Join(cfg.OutputDir, relPath)
		if err := writeFile(fullPath, content); err != nil {
			return nil, fmt.Errorf("write %s: %w", relPath, err)
		}

		result.Files = append(result.Files, GeneratedFile{
			Path:      relPath,
			Namespace: ns,
			CellIDs:   cellIDs,
		})
	}

	// Generate manifest files
	manifests, err := st.ListManifests()
	if err != nil {
		return nil, fmt.Errorf("list manifests: %w", err)
	}

	for _, ms := range manifests {
		manifest, err := st.GetLatestManifest(ms.ID)
		if err != nil {
			return nil, fmt.Errorf("load manifest %s: %w", ms.ID, err)
		}
		if manifest == nil {
			continue
		}

		// :order/placement -> order-placement
		cleanID := strings.TrimPrefix(ms.ID, ":")
		cleanID = strings.ReplaceAll(cleanID, "/", "-")

		ns := cfg.BaseNamespace + ".workflows." + cleanID
		relPath := nsToPath(ns)

		content := buildManifestNamespace(ns, cfg.BaseNamespace, manifest.Body, groups)
		fullPath := filepath.Join(cfg.OutputDir, relPath)
		if err := writeFile(fullPath, content); err != nil {
			return nil, fmt.Errorf("write %s: %w", relPath, err)
		}

		result.Files = append(result.Files, GeneratedFile{
			Path:      relPath,
			Namespace: ns,
		})
	}

	return &result, nil
}

// groupCellsByPrefix groups cell IDs by their namespace prefix.
// :order/validate-items -> "order", :core/start -> "core"
func groupCellsByPrefix(cells []store.CellSummary) map[string][]string {
	groups := make(map[string][]string)
	for _, c := range cells {
		prefix := extractPrefix(c.ID)
		groups[prefix] = append(groups[prefix], c.ID)
	}
	// Sort within each group for deterministic output
	for prefix := range groups {
		sort.Strings(groups[prefix])
	}
	return groups
}

// extractPrefix pulls the namespace prefix from a cell ID.
// ":order/validate-items" -> "order"
// ":payment-processing/charge" -> "payment-processing"
// ":validate" -> "core" (default for unprefixed)
// Hyphens are preserved — nsToPath handles the hyphen→underscore conversion for file paths.
func extractPrefix(cellID string) string {
	id := strings.TrimPrefix(cellID, ":")
	if idx := strings.Index(id, "/"); idx > 0 {
		return id[:idx]
	}
	return "core"
}

// nsToPath converts a Clojure namespace to a file path.
// "myapp.cells.order" -> "src/myapp/cells/order.clj"
func nsToPath(ns string) string {
	parts := strings.Split(ns, ".")
	path := filepath.Join(parts...)
	// Replace hyphens with underscores in path (Clojure convention)
	path = strings.ReplaceAll(path, "-", "_")
	return filepath.Join("src", path+".clj")
}

// buildCellNamespace generates a Clojure namespace file containing defcell forms.
func buildCellNamespace(ns string, handlers []string) string {
	var b strings.Builder
	fmt.Fprintf(&b, "(ns %s\n", ns)
	b.WriteString("  (:require [mycelium.cell :as cell]))\n")

	for _, handler := range handlers {
		b.WriteString("\n")
		b.WriteString(handler)
		b.WriteString("\n")
	}

	return b.String()
}

// buildManifestNamespace generates a Clojure namespace file that defines a workflow.
func buildManifestNamespace(ns, baseNs, manifestBody string, cellGroups map[string][]string) string {
	var b strings.Builder
	fmt.Fprintf(&b, "(ns %s\n", ns)
	b.WriteString("  (:require [mycelium.core :as myc]\n")
	b.WriteString("            [mycelium.cell :as cell]")

	// Require all cell namespaces so defcells are registered
	prefixes := make([]string, 0, len(cellGroups))
	for prefix := range cellGroups {
		prefixes = append(prefixes, prefix)
	}
	sort.Strings(prefixes)
	for _, prefix := range prefixes {
		cellNs := baseNs + ".cells." + prefix
		fmt.Fprintf(&b, "\n            [%s]", cellNs)
	}

	b.WriteString("))\n\n")
	fmt.Fprintf(&b, "(def manifest\n  %s)\n\n", manifestBody)
	b.WriteString("(defn compile-workflow\n")
	b.WriteString("  ([] (compile-workflow {}))\n")
	b.WriteString("  ([opts] (myc/compile-workflow manifest opts)))\n")

	return b.String()
}

// writeFile creates the necessary directories and writes content to a file.
func writeFile(path, content string) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return fmt.Errorf("mkdir %s: %w", dir, err)
	}
	return os.WriteFile(path, []byte(content), 0644)
}
