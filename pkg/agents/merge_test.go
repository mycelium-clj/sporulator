package agents

import (
	"strings"
	"testing"
)

func TestSplitDeftests(t *testing.T) {
	source := `(deftest test-alpha
  (is (= 1 1)))

(deftest test-beta
  (let [x 2]
    (is (= x 2))))

(deftest test-gamma
  (testing "nested"
    (is (= 3 3))))`

	tests := splitDeftests(source)
	if len(tests) != 3 {
		t.Fatalf("expected 3 tests, got %d", len(tests))
	}
	if extractDeftestName(tests[0]) != "test-alpha" {
		t.Errorf("expected test-alpha, got %s", extractDeftestName(tests[0]))
	}
	if extractDeftestName(tests[1]) != "test-beta" {
		t.Errorf("expected test-beta, got %s", extractDeftestName(tests[1]))
	}
	if extractDeftestName(tests[2]) != "test-gamma" {
		t.Errorf("expected test-gamma, got %s", extractDeftestName(tests[2]))
	}
}

func TestMergeTestCorrections_ReplacesMatchingTests(t *testing.T) {
	original := `(deftest test-alpha
  (is (= 1 1)))

(deftest test-beta
  (is (= 2 3)))

(deftest test-gamma
  (is (= 3 3)))`

	corrections := `(deftest test-beta
  (is (= 2 2)))`

	result := mergeTestCorrections(original, corrections)

	// test-alpha should be preserved
	if !strings.Contains(result, "(is (= 1 1))") {
		t.Error("test-alpha was not preserved")
	}
	// test-beta should be replaced
	if strings.Contains(result, "(is (= 2 3))") {
		t.Error("old test-beta was not replaced")
	}
	if !strings.Contains(result, "(is (= 2 2))") {
		t.Error("corrected test-beta not found")
	}
	// test-gamma should be preserved
	if !strings.Contains(result, "(is (= 3 3))") {
		t.Error("test-gamma was not preserved")
	}

	// Should still have 3 tests
	tests := splitDeftests(result)
	if len(tests) != 3 {
		t.Errorf("expected 3 tests after merge, got %d", len(tests))
	}
}

func TestMergeTestCorrections_AppendsNewTests(t *testing.T) {
	original := `(deftest test-alpha
  (is (= 1 1)))`

	corrections := `(deftest test-new
  (is (= 42 42)))`

	result := mergeTestCorrections(original, corrections)

	tests := splitDeftests(result)
	if len(tests) != 2 {
		t.Errorf("expected 2 tests after merge, got %d", len(tests))
	}
	if !strings.Contains(result, "test-alpha") {
		t.Error("original test-alpha missing")
	}
	if !strings.Contains(result, "test-new") {
		t.Error("new test not appended")
	}
}

func TestMergeTestCorrections_EmptyCorrections(t *testing.T) {
	original := `(deftest test-alpha
  (is (= 1 1)))`

	result := mergeTestCorrections(original, "")
	if result != original {
		t.Error("empty corrections should return original unchanged")
	}
}
