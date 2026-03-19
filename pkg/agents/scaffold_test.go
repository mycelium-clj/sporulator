package agents

import (
	"strings"
	"testing"
)

func TestAssembleCellSource(t *testing.T) {
	result := assembleCellSource(
		"example.cells.order", ":order/validate", "Validate input",
		"{:input [:map] :output [:map]}",
		[]string{"db"},
		[]string{"[clojure.string :as str]"},
		"(defn helper [x] x)",
		"(fn [resources data] data)",
	)

	if !strings.Contains(result, "(ns example.cells.order") {
		t.Error("should contain ns declaration")
	}
	if !strings.Contains(result, "[mycelium.cell :as cell]") {
		t.Error("should require mycelium.cell")
	}
	if !strings.Contains(result, "[clojure.string :as str]") {
		t.Error("should contain extra require")
	}
	if !strings.Contains(result, "(defn helper [x] x)") {
		t.Error("should contain helper")
	}
	if !strings.Contains(result, "(cell/defcell :order/validate") {
		t.Error("should contain defcell with correct ID")
	}
	if !strings.Contains(result, `"Validate input"`) {
		t.Error("should contain doc string")
	}
	if !strings.Contains(result, "(fn [resources data] data)") {
		t.Error("should contain fn body")
	}
}

func TestAssembleCellSourceNoHelpers(t *testing.T) {
	result := assembleCellSource(
		"example.cells.order", ":order/validate", "Validate",
		"{:input [:map] :output [:map]}",
		nil, nil, "",
		"(fn [resources data] data)",
	)

	// Should not have double newlines from empty helpers
	if strings.Contains(result, "\n\n\n") {
		t.Error("should not have triple newlines")
	}
	if !strings.Contains(result, "(cell/defcell :order/validate") {
		t.Error("should contain defcell")
	}
}

func TestAssembleCellSourceEscapesDocQuotes(t *testing.T) {
	result := assembleCellSource(
		"example.cells.order", ":order/validate", `Validate "special" input`,
		"{:input [:map] :output [:map]}",
		nil, nil, "",
		"(fn [resources data] data)",
	)

	if !strings.Contains(result, `"Validate \"special\" input"`) {
		t.Errorf("should escape quotes in doc string, got:\n%s", result)
	}
}

func TestAssembleTestSource(t *testing.T) {
	result := assembleTestSource(
		"example.cells.order-test",
		"example.cells.order",
		":order/validate",
		"(deftest test-basic\n  (is (= 1 1)))",
	)

	if !strings.Contains(result, "(ns example.cells.order-test") {
		t.Error("should contain test ns")
	}
	if !strings.Contains(result, "[clojure.test :refer [deftest is testing]]") {
		t.Error("should require clojure.test")
	}
	if !strings.Contains(result, "[example.cells.order]") {
		t.Error("should require cell ns")
	}
	if !strings.Contains(result, "(def handler (:handler cell-spec))") {
		t.Error("should bind handler")
	}
	if !strings.Contains(result, "(def cell-spec (cell/get-cell! :order/validate))") {
		t.Error("should bind cell-spec")
	}
	if !strings.Contains(result, "(defn approx=") {
		t.Error("should include approx= helper")
	}
	if !strings.Contains(result, "(deftest test-basic") {
		t.Error("should contain test body")
	}
}
