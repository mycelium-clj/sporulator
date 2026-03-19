// Package api provides the HTTP/WebSocket server for the Sporulator frontend.
package api

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"sync"

	"github.com/mycelium-clj/sporulator/pkg/agents"
	"github.com/mycelium-clj/sporulator/pkg/bridge"
	"github.com/mycelium-clj/sporulator/pkg/source"
	"github.com/mycelium-clj/sporulator/pkg/store"
)

// reviewGate is a channel-based gate for blocking until the user responds to a test review.
type reviewGate struct {
	ch chan []agents.ReviewResponse
}

// graphReviewGate is a channel-based gate for blocking until the user responds to a graph review.
type graphReviewGate struct {
	ch chan *agents.GraphReviewResponse
}

// Server is the Sporulator HTTP/WebSocket server.
type Server struct {
	store             *store.Store
	manager           *agents.Manager
	bridgeMu          sync.RWMutex
	bridge            *bridge.Bridge // nil if no REPL connected
	hub               *Hub
	mux               *http.ServeMux
	reviewGates       map[string]*reviewGate      // runID → gate
	reviewGatesMu     sync.Mutex
	graphReviewGates  map[string]*graphReviewGate  // runID → gate
	graphReviewMu     sync.Mutex
	implReviewGates   map[string]*implReviewGate   // runID → gate
	implReviewMu      sync.Mutex
}

// Config configures the API server.
type Config struct {
	Store   *store.Store
	Manager *agents.Manager
	Bridge  *bridge.Bridge // optional, can be nil
}

// NewServer creates a new API server.
func NewServer(cfg Config) *Server {
	s := &Server{
		store:       cfg.Store,
		manager:     cfg.Manager,
		bridge:      cfg.Bridge,
		hub:         NewHub(),
		mux:         http.NewServeMux(),
		reviewGates:      make(map[string]*reviewGate),
		graphReviewGates: make(map[string]*graphReviewGate),
		implReviewGates:  make(map[string]*implReviewGate),
	}
	s.routes()
	go s.hub.Run()
	return s
}

// ServeHTTP implements http.Handler.
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s.mux.ServeHTTP(w, r)
}

// SetBridge connects or replaces the REPL bridge.
func (s *Server) SetBridge(b *bridge.Bridge) {
	s.bridgeMu.Lock()
	defer s.bridgeMu.Unlock()
	s.bridge = b
}

// getBridge returns the current bridge (thread-safe).
func (s *Server) getBridge() *bridge.Bridge {
	s.bridgeMu.RLock()
	defer s.bridgeMu.RUnlock()
	return s.bridge
}

func (s *Server) routes() {
	// Cells — IDs contain slashes (e.g. "math/double"), so use query params
	s.mux.HandleFunc("GET /api/cells", s.handleListCells)
	s.mux.HandleFunc("GET /api/cell", s.handleGetCell)
	s.mux.HandleFunc("POST /api/cell", s.handleSaveCell)
	s.mux.HandleFunc("GET /api/cell/history", s.handleCellHistory)
	s.mux.HandleFunc("GET /api/cell/tests", s.handleCellTests)

	// Manifests — same pattern
	s.mux.HandleFunc("GET /api/manifests", s.handleListManifests)
	s.mux.HandleFunc("GET /api/manifest", s.handleGetManifest)
	s.mux.HandleFunc("POST /api/manifest", s.handleSaveManifest)

	// REPL
	s.mux.HandleFunc("POST /api/repl/eval", s.handleReplEval)
	s.mux.HandleFunc("POST /api/repl/instantiate", s.handleReplInstantiate)
	s.mux.HandleFunc("GET /api/repl/status", s.handleReplStatus)

	// Source generation
	s.mux.HandleFunc("POST /api/source/generate", s.handleSourceGenerate)

	// WebSocket
	s.mux.HandleFunc("GET /ws", s.handleWebSocket)
}

// ListenAndServe starts the server on the given address.
func (s *Server) ListenAndServe(addr string) error {
	log.Printf("Sporulator API listening on %s", addr)
	return http.ListenAndServe(addr, s)
}

// --- JSON helpers ---

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func readJSON(r *http.Request, v any) error {
	return json.NewDecoder(r.Body).Decode(v)
}

// --- Cell handlers ---

func (s *Server) handleListCells(w http.ResponseWriter, r *http.Request) {
	cells, err := s.store.ListCells()
	if err != nil {
		writeError(w, 500, fmt.Sprintf("list cells: %v", err))
		return
	}
	if cells == nil {
		cells = []store.CellSummary{}
	}
	writeJSON(w, 200, cells)
}

func cellID(r *http.Request) string {
	id := r.URL.Query().Get("id")
	if id != "" && id[0] != ':' {
		id = ":" + id
	}
	return id
}

func manifestID(r *http.Request) string {
	id := r.URL.Query().Get("id")
	if id != "" && id[0] != ':' {
		id = ":" + id
	}
	return id
}

func (s *Server) handleGetCell(w http.ResponseWriter, r *http.Request) {
	id := cellID(r)
	if id == "" {
		writeError(w, 400, "id parameter is required")
		return
	}

	cell, err := s.store.GetLatestCell(id)
	if err != nil {
		writeError(w, 500, fmt.Sprintf("get cell: %v", err))
		return
	}
	if cell == nil {
		writeError(w, 404, "cell not found")
		return
	}
	writeJSON(w, 200, cell)
}

type saveCellRequest struct {
	Handler   string `json:"handler"`
	Schema    string `json:"schema"`
	Doc       string `json:"doc"`
	Requires  string `json:"requires"`
	CreatedBy string `json:"created_by"`
}

func (s *Server) handleSaveCell(w http.ResponseWriter, r *http.Request) {
	id := cellID(r)
	if id == "" {
		writeError(w, 400, "id parameter is required")
		return
	}

	var req saveCellRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, 400, fmt.Sprintf("invalid json: %v", err))
		return
	}
	if req.Handler == "" {
		writeError(w, 400, "handler is required")
		return
	}

	version, err := s.store.SaveCell(&store.Cell{
		ID:        id,
		Handler:   req.Handler,
		Schema:    req.Schema,
		Doc:       req.Doc,
		Requires:  req.Requires,
		CreatedBy: req.CreatedBy,
	})
	if err != nil {
		writeError(w, 500, fmt.Sprintf("save cell: %v", err))
		return
	}

	writeJSON(w, 201, map[string]any{"id": id, "version": version})
}

func (s *Server) handleCellHistory(w http.ResponseWriter, r *http.Request) {
	id := cellID(r)
	if id == "" {
		writeError(w, 400, "id parameter is required")
		return
	}

	history, err := s.store.GetCellHistory(id)
	if err != nil {
		writeError(w, 500, fmt.Sprintf("cell history: %v", err))
		return
	}
	if history == nil {
		history = []store.Cell{}
	}
	writeJSON(w, 200, history)
}

func (s *Server) handleCellTests(w http.ResponseWriter, r *http.Request) {
	id := cellID(r)
	if id == "" {
		writeError(w, 400, "id parameter is required")
		return
	}

	cell, err := s.store.GetLatestCell(id)
	if err != nil || cell == nil {
		writeJSON(w, 200, []store.TestResult{})
		return
	}

	results, err := s.store.GetTestResults(id, cell.Version)
	if err != nil {
		writeError(w, 500, fmt.Sprintf("test results: %v", err))
		return
	}
	if results == nil {
		results = []store.TestResult{}
	}
	writeJSON(w, 200, results)
}

// --- Manifest handlers ---

func (s *Server) handleListManifests(w http.ResponseWriter, r *http.Request) {
	manifests, err := s.store.ListManifests()
	if err != nil {
		writeError(w, 500, fmt.Sprintf("list manifests: %v", err))
		return
	}
	if manifests == nil {
		manifests = []store.ManifestSummary{}
	}
	writeJSON(w, 200, manifests)
}

func (s *Server) handleGetManifest(w http.ResponseWriter, r *http.Request) {
	id := manifestID(r)
	if id == "" {
		writeError(w, 400, "id parameter is required")
		return
	}

	manifest, err := s.store.GetLatestManifest(id)
	if err != nil {
		writeError(w, 500, fmt.Sprintf("get manifest: %v", err))
		return
	}
	if manifest == nil {
		writeError(w, 404, "manifest not found")
		return
	}
	writeJSON(w, 200, manifest)
}

type saveManifestRequest struct {
	Body      string `json:"body"`
	CreatedBy string `json:"created_by"`
}

func (s *Server) handleSaveManifest(w http.ResponseWriter, r *http.Request) {
	id := manifestID(r)
	if id == "" {
		writeError(w, 400, "id parameter is required")
		return
	}

	var req saveManifestRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, 400, fmt.Sprintf("invalid json: %v", err))
		return
	}
	if req.Body == "" {
		writeError(w, 400, "body is required")
		return
	}

	version, err := s.store.SaveManifest(&store.Manifest{
		ID:        id,
		Body:      req.Body,
		CreatedBy: req.CreatedBy,
	})
	if err != nil {
		writeError(w, 500, fmt.Sprintf("save manifest: %v", err))
		return
	}

	writeJSON(w, 201, map[string]any{"id": id, "version": version})
}

// --- REPL handlers ---

type evalRequest struct {
	Code string `json:"code"`
	Ns   string `json:"ns"`
}

func (s *Server) handleReplEval(w http.ResponseWriter, r *http.Request) {
	b := s.getBridge()
	if b == nil {
		writeError(w, 503, "no REPL connected")
		return
	}

	var req evalRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, 400, fmt.Sprintf("invalid json: %v", err))
		return
	}

	var result *bridge.EvalResult
	var err error
	if req.Ns != "" {
		result, err = b.EvalInNs(req.Ns, req.Code)
	} else {
		result, err = b.Eval(req.Code)
	}
	if err != nil {
		writeError(w, 500, fmt.Sprintf("eval: %v", err))
		return
	}

	writeJSON(w, 200, result)
}

type instantiateRequest struct {
	CellID  string `json:"cell_id"`
	Version int    `json:"version"` // 0 means latest
}

func (s *Server) handleReplInstantiate(w http.ResponseWriter, r *http.Request) {
	b := s.getBridge()
	if b == nil {
		writeError(w, 503, "no REPL connected")
		return
	}

	var req instantiateRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, 400, fmt.Sprintf("invalid json: %v", err))
		return
	}

	var result *bridge.EvalResult
	var err error
	if req.Version > 0 {
		result, err = b.InstantiateCellVersion(req.CellID, req.Version)
	} else {
		result, err = b.InstantiateCellFromStore(req.CellID)
	}
	if err != nil {
		writeError(w, 500, fmt.Sprintf("instantiate: %v", err))
		return
	}
	if result.IsError() {
		writeJSON(w, 422, result)
		return
	}

	writeJSON(w, 200, map[string]any{
		"cell_id": req.CellID,
		"status":  "instantiated",
		"value":   result.Value,
	})
}

func (s *Server) handleReplStatus(w http.ResponseWriter, r *http.Request) {
	connected := false
	if b := s.getBridge(); b != nil {
		connected = b.Connected()
	}
	writeJSON(w, 200, map[string]any{"connected": connected})
}

// --- Source generation handler ---

type sourceGenerateRequest struct {
	OutputDir     string `json:"output_dir"`
	BaseNamespace string `json:"base_namespace"`
}

func (s *Server) handleSourceGenerate(w http.ResponseWriter, r *http.Request) {
	var req sourceGenerateRequest
	if err := readJSON(r, &req); err != nil {
		writeError(w, 400, fmt.Sprintf("invalid json: %v", err))
		return
	}
	if req.OutputDir == "" {
		writeError(w, 400, "output_dir is required")
		return
	}
	if req.BaseNamespace == "" {
		writeError(w, 400, "base_namespace is required")
		return
	}

	result, err := source.Generate(s.store, source.Config{
		OutputDir:     req.OutputDir,
		BaseNamespace: req.BaseNamespace,
	})
	if err != nil {
		writeError(w, 500, fmt.Sprintf("generate: %v", err))
		return
	}

	writeJSON(w, 200, result)
}
