package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"dev.pockethost/daemons/internal/pocket"
)

func testGateway(token string, base map[string]string) *gateway {
	return &gateway{
		client: &http.Client{Timeout: 2 * time.Second},
		token:  token,
		base:   base,
	}
}

func TestServicesAggregatesHealthAndErrors(t *testing.T) {
	healthy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		pocket.WriteJSON(w, http.StatusOK, pocket.Health{
			Service: "web", Status: "ok", Version: "9.9.9", UptimeSeconds: 42,
			Addr: "127.0.0.1:8080",
		})
	}))
	defer healthy.Close()

	// "files" points nowhere reachable so it should report down.
	gw := testGateway("", map[string]string{
		"web":   healthy.URL,
		"files": "http://127.0.0.1:1", // refused
	})

	rr := httptest.NewRecorder()
	gw.servicesHandler(rr, httptest.NewRequest(http.MethodGet, "/api/services", nil))
	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	var resp struct {
		Services []serviceStatus `json:"services"`
	}
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	byID := map[string]serviceStatus{}
	for _, s := range resp.Services {
		byID[s.ID] = s
	}
	if len(resp.Services) != len(registry) {
		t.Fatalf("expected %d services, got %d", len(registry), len(resp.Services))
	}
	web := byID["web"]
	if !web.OK || web.Version != "9.9.9" || web.UptimeSeconds != 42 || web.Addr != "127.0.0.1:8080" {
		t.Fatalf("web aggregation wrong: %#v", web)
	}
	if files := byID["files"]; files.OK || files.Error == "" {
		t.Fatalf("files should be down with an error: %#v", files)
	}
	if host := byID["host"]; host.OK || host.Error != "no base url" {
		t.Fatalf("host without base url should report no base url: %#v", host)
	}
}

func TestGatewayForwardsAdminTokenToSibling(t *testing.T) {
	var gotToken string
	var gotMethod string
	ddns := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotToken = r.Header.Get("X-PocketHost-Token")
		gotMethod = r.Method
		pocket.WriteJSON(w, http.StatusOK, map[string]string{"status": "updated"})
	}))
	defer ddns.Close()

	gw := testGateway("admin-secret", map[string]string{"ddns": ddns.URL})
	rr := httptest.NewRecorder()
	gw.ddnsUpdateHandler(rr, httptest.NewRequest(http.MethodPost, "/api/ddns/update-now", nil))

	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	if gotToken != "admin-secret" {
		t.Fatalf("sibling did not receive admin token, got %q", gotToken)
	}
	if gotMethod != http.MethodPost {
		t.Fatalf("expected POST forwarded, got %s", gotMethod)
	}
	if !strings.Contains(rr.Body.String(), "updated") {
		t.Fatalf("upstream body not relayed: %s", rr.Body.String())
	}
}

func TestGatewayForwardsFilesPathAndToken(t *testing.T) {
	var gotPath, gotToken string
	files := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotToken = r.Header.Get("X-PocketHost-Token")
		gotPath = r.URL.Query().Get("path")
		pocket.WriteJSON(w, http.StatusOK, map[string]any{"items": []any{}})
	}))
	defer files.Close()

	gw := testGateway("tok", map[string]string{"files": files.URL})
	rr := httptest.NewRecorder()
	gw.filesListHandler(rr, httptest.NewRequest(http.MethodGet, "/api/files?path=sub/dir", nil))
	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	if gotPath != "sub/dir" {
		t.Fatalf("path not forwarded, got %q", gotPath)
	}
	if gotToken != "tok" {
		t.Fatalf("token not forwarded, got %q", gotToken)
	}
}

func TestFilesDeleteRejectsTraversalWithoutForwarding(t *testing.T) {
	forwarded := false
	files := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		forwarded = true
		pocket.WriteJSON(w, http.StatusOK, map[string]string{"status": "deleted"})
	}))
	defer files.Close()

	gw := testGateway("tok", map[string]string{"files": files.URL})
	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/files/delete", strings.NewReader(`{"path":"../escape.txt"}`))
	gw.filesDeleteHandler(rr, req)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("traversal should be rejected, got %d", rr.Code)
	}
	if forwarded {
		t.Fatalf("hostile path must not be forwarded to filed")
	}
}

func TestCleanRelPath(t *testing.T) {
	good := map[string]string{"": "", "a/b.txt": "a/b.txt", "dir": "dir", "a\\b": "a/b"}
	for in, want := range good {
		got, err := cleanRelPath(in)
		if err != nil || got != want {
			t.Fatalf("cleanRelPath(%q)=%q,%v want %q", in, got, err, want)
		}
	}
	bad := []string{"/etc/passwd", "../x", "a/../../b", "\\abs"}
	for _, in := range bad {
		if _, err := cleanRelPath(in); err == nil {
			t.Fatalf("cleanRelPath(%q) should have failed", in)
		}
	}
}

func TestPanelAndApiTokenGating(t *testing.T) {
	h := buildHandler("127.0.0.1:0", time.Now(), "secret", testGateway("secret", map[string]string{}))

	// Static panel is public.
	page := httptest.NewRecorder()
	h.ServeHTTP(page, httptest.NewRequest(http.MethodGet, "/", nil))
	if page.Code != http.StatusOK || !strings.Contains(page.Body.String(), "PocketHost") {
		t.Fatalf("panel not served: status=%d", page.Code)
	}

	// Health is public.
	health := httptest.NewRecorder()
	h.ServeHTTP(health, httptest.NewRequest(http.MethodGet, "/health", nil))
	if health.Code != http.StatusOK {
		t.Fatalf("health status=%d", health.Code)
	}

	// /api/services requires the token.
	noTok := httptest.NewRecorder()
	h.ServeHTTP(noTok, httptest.NewRequest(http.MethodGet, "/api/services", nil))
	if noTok.Code != http.StatusUnauthorized {
		t.Fatalf("services without token=%d", noTok.Code)
	}
	withTok := httptest.NewRequest(http.MethodGet, "/api/services", nil)
	withTok.Header.Set("X-PocketHost-Token", "secret")
	ok := httptest.NewRecorder()
	h.ServeHTTP(ok, withTok)
	if ok.Code != http.StatusOK {
		t.Fatalf("services with token=%d body=%s", ok.Code, ok.Body.String())
	}
}
