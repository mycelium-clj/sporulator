package source

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/mycelium-clj/sporulator/pkg/store"
)

func TestGenerate(t *testing.T) {
	st, err := store.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	// Save two cells with same prefix
	st.SaveCell(&store.Cell{
		ID:      ":order/validate-items",
		Handler: `(cell/defcell :order/validate-items {:schema {:input {:items [:vector :any]} :output {:validated-items [:vector :any]}}} (fn [resources data] {:validated-items (:items data)}))`,
		Schema:  `{:input {:items [:vector :any]} :output {:validated-items [:vector :any]}}`,
		Doc:     "Validate items",
	})
	st.SaveCell(&store.Cell{
		ID:      ":order/compute-tax",
		Handler: `(cell/defcell :order/compute-tax {:schema {:input {:subtotal :double} :output {:tax :double}}} (fn [resources data] {:tax (* 0.1 (:subtotal data))}))`,
		Schema:  `{:input {:subtotal :double} :output {:tax :double}}`,
		Doc:     "Compute tax",
	})
	// A cell with a different prefix
	st.SaveCell(&store.Cell{
		ID:      ":core/start",
		Handler: `(cell/defcell :core/start {:schema {:input {:x :int} :output {:x :int}}} (fn [resources data] data))`,
	})

	// Save a manifest
	st.SaveManifest(&store.Manifest{
		ID:   ":order/placement",
		Body: `{:id :order/placement :pipeline [:validate-items :compute-tax]}`,
	})

	outDir := t.TempDir()

	result, err := Generate(st, Config{
		OutputDir:     outDir,
		BaseNamespace: "myapp",
	})
	if err != nil {
		t.Fatal(err)
	}

	if len(result.Files) != 3 {
		t.Fatalf("expected 3 files, got %d: %+v", len(result.Files), result.Files)
	}

	// Check order cells file exists
	orderPath := filepath.Join(outDir, "src", "myapp", "cells", "order.clj")
	data, err := os.ReadFile(orderPath)
	if err != nil {
		t.Fatalf("order cells file not found: %v", err)
	}
	content := string(data)
	if !strings.Contains(content, "(ns myapp.cells.order") {
		t.Error("order cells file missing namespace declaration")
	}
	if !strings.Contains(content, "cell/defcell :order/validate-items") {
		t.Error("order cells file missing validate-items defcell")
	}
	if !strings.Contains(content, "cell/defcell :order/compute-tax") {
		t.Error("order cells file missing compute-tax defcell")
	}

	// Check core cells file
	corePath := filepath.Join(outDir, "src", "myapp", "cells", "core.clj")
	data, err = os.ReadFile(corePath)
	if err != nil {
		t.Fatalf("core cells file not found: %v", err)
	}
	if !strings.Contains(string(data), "cell/defcell :core/start") {
		t.Error("core cells file missing start defcell")
	}

	// Check manifest file
	manifestPath := filepath.Join(outDir, "src", "myapp", "workflows", "order_placement.clj")
	data, err = os.ReadFile(manifestPath)
	if err != nil {
		t.Fatalf("manifest file not found: %v", err)
	}
	mContent := string(data)
	if !strings.Contains(mContent, "(ns myapp.workflows.order-placement") {
		t.Error("manifest file missing namespace declaration")
	}
	if !strings.Contains(mContent, "def manifest") {
		t.Error("manifest file missing manifest def")
	}
	if !strings.Contains(mContent, "[myapp.cells.order]") {
		t.Error("manifest file missing cell namespace require")
	}
}

func TestExtractPrefix(t *testing.T) {
	tests := []struct {
		id   string
		want string
	}{
		{":order/validate-items", "order"},
		{":core/start", "core"},
		{":validate", "core"},
		{":payment-processing/charge", "payment-processing"},
	}
	for _, tt := range tests {
		got := extractPrefix(tt.id)
		if got != tt.want {
			t.Errorf("extractPrefix(%q) = %q, want %q", tt.id, got, tt.want)
		}
	}
}

func TestNsToPath(t *testing.T) {
	tests := []struct {
		ns   string
		want string
	}{
		{"myapp.cells.order", filepath.Join("src", "myapp", "cells", "order.clj")},
		{"myapp.workflows.order-placement", filepath.Join("src", "myapp", "workflows", "order_placement.clj")},
	}
	for _, tt := range tests {
		got := nsToPath(tt.ns)
		if got != tt.want {
			t.Errorf("nsToPath(%q) = %q, want %q", tt.ns, got, tt.want)
		}
	}
}
