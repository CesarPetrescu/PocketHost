package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"

	"dev.pockethost/daemons/internal/pocket"
)

// daemonInfo describes a sibling daemon that the host control panel can observe.
// It mirrors the Android ServiceRegistry so the web panel and the in-app
// manager present the same fleet.
type daemonInfo struct {
	ID         string
	Name       string
	Port       int
	HealthPath string
	// Actionable reports which control actions the web panel may invoke for this
	// daemon. Process start/stop is intentionally absent: only the Android
	// foreground supervisor may launch or kill daemons (AGENTS.md rule 3).
	Actions []string
}

// registry is the canonical local fleet. Ports match docs/ARCHITECTURE.md.
var registry = []daemonInfo{
	{ID: "host", Name: "Host API", Port: 8099, HealthPath: "/health"},
	{ID: "web", Name: "Web Server", Port: 8080, HealthPath: "/health"},
	{ID: "files", Name: "MiniCloud Files", Port: 8090, HealthPath: "/health", Actions: []string{"browse"}},
	{ID: "proxy", Name: "Reverse Proxy", Port: 8088, HealthPath: "/health"},
	{ID: "ddns", Name: "DDNS Updater", Port: 8091, HealthPath: "/health", Actions: []string{"update-now"}},
	{ID: "matrix", Name: "Matrix Server", Port: 6167, HealthPath: "/_matrix/client/versions"},
}

func daemonByID(id string) (daemonInfo, bool) {
	for _, d := range registry {
		if d.ID == id {
			return d, true
		}
	}
	return daemonInfo{}, false
}

// gateway aggregates and proxies to sibling daemons over loopback. It holds the
// admin token so it can authenticate outbound control calls; the token is never
// sent to the browser.
type gateway struct {
	client *http.Client
	token  string
	base   map[string]string // daemon id -> base URL, e.g. http://127.0.0.1:8080
}

func defaultGateway(token string) *gateway {
	base := make(map[string]string, len(registry))
	for _, d := range registry {
		base[d.ID] = fmt.Sprintf("http://127.0.0.1:%d", d.Port)
	}
	return &gateway{
		client: &http.Client{Timeout: 4 * time.Second},
		token:  token,
		base:   base,
	}
}

// serviceStatus is one row in the aggregated status grid.
type serviceStatus struct {
	ID            string            `json:"id"`
	Name          string            `json:"name"`
	Port          int               `json:"port"`
	Endpoint      string            `json:"endpoint"`
	OK            bool              `json:"ok"`
	Status        string            `json:"status"`
	Version       string            `json:"version,omitempty"`
	UptimeSeconds int64             `json:"uptime_seconds,omitempty"`
	Addr          string            `json:"addr,omitempty"`
	Extra         map[string]string `json:"extra,omitempty"`
	Actions       []string          `json:"actions,omitempty"`
	Error         string            `json:"error,omitempty"`
}

// servicesHandler returns the live status of every known daemon. Probes run
// concurrently and never block longer than the gateway client timeout.
func (g *gateway) servicesHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		pocket.WriteError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	results := make([]serviceStatus, len(registry))
	var wg sync.WaitGroup
	for i, d := range registry {
		wg.Add(1)
		go func(i int, d daemonInfo) {
			defer wg.Done()
			results[i] = g.probe(r.Context(), d)
		}(i, d)
	}
	wg.Wait()
	sort.SliceStable(results, func(i, j int) bool { return results[i].ID < results[j].ID })
	pocket.WriteJSON(w, http.StatusOK, map[string]any{
		"time_utc": time.Now().UTC().Format(time.RFC3339),
		"services": results,
	})
}

func (g *gateway) probe(ctx context.Context, d daemonInfo) serviceStatus {
	out := serviceStatus{
		ID:       d.ID,
		Name:     d.Name,
		Port:     d.Port,
		Endpoint: fmt.Sprintf("http://127.0.0.1:%d", d.Port),
		Actions:  d.Actions,
		Status:   "down",
	}
	base, ok := g.base[d.ID]
	if !ok {
		out.Error = "no base url"
		return out
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+d.HealthPath, nil)
	if err != nil {
		out.Error = err.Error()
		return out
	}
	resp, err := g.client.Do(req)
	if err != nil {
		out.Error = "unreachable"
		return out
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 16<<10))
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		out.Error = fmt.Sprintf("health %d", resp.StatusCode)
		return out
	}
	out.OK = true
	out.Status = "up"
	var h pocket.Health
	if err := json.Unmarshal(body, &h); err == nil {
		if h.Status != "" {
			out.Status = h.Status
		}
		out.Version = h.Version
		out.UptimeSeconds = h.UptimeSeconds
		out.Addr = h.Addr
		out.Extra = h.Extra
	}
	return out
}

// ddnsUpdateHandler forwards a manual DDNS refresh to ddnsd, authenticating with
// the admin token held server-side.
func (g *gateway) ddnsUpdateHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		pocket.WriteError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	g.relay(w, r.Context(), http.MethodPost, "ddns", "/api/update-now", "", nil)
}

// filesListHandler proxies a directory listing from filed.
func (g *gateway) filesListHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		pocket.WriteError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	rel, err := cleanRelPath(r.URL.Query().Get("path"))
	if err != nil {
		pocket.WriteError(w, http.StatusBadRequest, err.Error())
		return
	}
	target := "/api/files"
	if rel != "" {
		target += "?path=" + url.QueryEscape(rel)
	}
	g.relay(w, r.Context(), http.MethodGet, "files", target, "", nil)
}

// filesDownloadHandler streams a file download from filed. The browser fetches
// this with the admin token header and saves the blob, so the token never
// appears in a URL or the page DOM.
func (g *gateway) filesDownloadHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		pocket.WriteError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	rel, err := cleanRelPath(r.URL.Query().Get("path"))
	if err != nil || rel == "" {
		pocket.WriteError(w, http.StatusBadRequest, "path is required and must stay within the file root")
		return
	}
	g.relay(w, r.Context(), http.MethodGet, "files", "/files/"+pathEscapeSegments(rel), "", nil)
}

// filesDeleteHandler proxies a delete to filed.
func (g *gateway) filesDeleteHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost && r.Method != http.MethodDelete {
		pocket.WriteError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	var body struct {
		Path string `json:"path"`
	}
	_ = json.NewDecoder(io.LimitReader(r.Body, 8<<10)).Decode(&body)
	rel, err := cleanRelPath(body.Path)
	if err != nil || rel == "" {
		pocket.WriteError(w, http.StatusBadRequest, "path is required and must stay within the file root")
		return
	}
	payload, _ := json.Marshal(map[string]string{"path": rel})
	g.relay(w, r.Context(), http.MethodPost, "files", "/api/delete", "application/json", strings.NewReader(string(payload)))
}

// filesUploadHandler streams a multipart upload through to filed.
func (g *gateway) filesUploadHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		pocket.WriteError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	rel, err := cleanRelPath(r.URL.Query().Get("path"))
	if err != nil {
		pocket.WriteError(w, http.StatusBadRequest, err.Error())
		return
	}
	target := "/api/upload"
	if rel != "" {
		target += "?path=" + url.QueryEscape(rel)
	}
	defer r.Body.Close()
	g.relay(w, r.Context(), http.MethodPost, "files", target, r.Header.Get("Content-Type"), r.Body)
}

// relay performs an authenticated request to a sibling daemon and copies the
// upstream status, content type, and body back to the caller.
func (g *gateway) relay(w http.ResponseWriter, ctx context.Context, method, id, path, contentType string, body io.Reader) {
	base, ok := g.base[id]
	if !ok {
		pocket.WriteError(w, http.StatusNotFound, "unknown service")
		return
	}
	req, err := http.NewRequestWithContext(ctx, method, base+path, body)
	if err != nil {
		pocket.WriteError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	if g.token != "" {
		req.Header.Set("X-PocketHost-Token", g.token)
	}
	resp, err := g.client.Do(req)
	if err != nil {
		pocket.WriteError(w, http.StatusBadGateway, "service unreachable")
		return
	}
	defer resp.Body.Close()
	if ct := resp.Header.Get("Content-Type"); ct != "" {
		w.Header().Set("Content-Type", ct)
	}
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, io.LimitReader(resp.Body, 32<<20))
}

// cleanRelPath rejects absolute paths and parent-directory traversal before a
// path is forwarded to filed. filed validates again with SafeExistingPath; this
// is defense in depth so the gateway never forwards an obviously hostile path.
func cleanRelPath(p string) (string, error) {
	p = strings.TrimSpace(p)
	if p == "" {
		return "", nil
	}
	if strings.HasPrefix(p, "/") || strings.HasPrefix(p, "\\") {
		return "", fmt.Errorf("absolute paths are not allowed")
	}
	p = strings.ReplaceAll(p, "\\", "/")
	for _, part := range strings.Split(p, "/") {
		if part == ".." {
			return "", fmt.Errorf("path escapes file root")
		}
	}
	return p, nil
}

// pathEscapeSegments percent-encodes each path segment while preserving the
// slashes, so a cleaned relative path can be appended to filed's /files/ route.
func pathEscapeSegments(p string) string {
	parts := strings.Split(p, "/")
	for i, part := range parts {
		parts[i] = url.PathEscape(part)
	}
	return strings.Join(parts, "/")
}
