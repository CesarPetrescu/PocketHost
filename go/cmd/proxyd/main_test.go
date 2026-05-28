package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestParseRoutes(t *testing.T) {
	routes := parseRoutes("web.local=http://127.0.0.1:8080, files.local = http://127.0.0.1:8090,broken")
	if routes["web.local"] != "http://127.0.0.1:8080" {
		t.Fatalf("web route missing: %#v", routes)
	}
	if routes["files.local"] != "http://127.0.0.1:8090" {
		t.Fatalf("files route missing: %#v", routes)
	}
	if _, ok := routes["broken"]; ok {
		t.Fatalf("broken route should have been ignored")
	}
}

func TestParseRoutesDropsInvalidHostsAndTargets(t *testing.T) {
	routes := parseRoutes("bad/path=http://127.0.0.1:1, ok.local=ftp://127.0.0.1:2, none.local=http://0.0.0.0:3, good.local=https://example.test")
	if len(routes) != 1 || routes["good.local"] != "https://example.test" {
		t.Fatalf("unexpected routes: %#v", routes)
	}
}

func TestNormalizeHost(t *testing.T) {
	if got := normalizeHost(" Web.Local. "); got != "web.local" {
		t.Fatalf("got %q", got)
	}
}

func TestValidateTargetURL(t *testing.T) {
	bad := []string{"", "127.0.0.1:8080", "ftp://127.0.0.1", "http://0.0.0.0:8080", "http://[::]:8080"}
	for _, target := range bad {
		if err := validateTargetURL(target); err == nil {
			t.Fatalf("validateTargetURL(%q) unexpectedly succeeded", target)
		}
	}
	if err := validateTargetURL("http://127.0.0.1:8080"); err != nil {
		t.Fatalf("valid local target rejected: %v", err)
	}
}

func TestProxyForwardsHostAndAddsUpstreamHeader(t *testing.T) {
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("X-PocketHost-Forwarded-Host"); got != "web.local" {
			t.Fatalf("forwarded host=%q", got)
		}
		w.WriteHeader(http.StatusAccepted)
		_, _ = w.Write([]byte("ok"))
	}))
	defer backend.Close()

	h := newProxyHandler("127.0.0.1:0", time.Now(), map[string]string{"web.local": backend.URL}, discardLogger{})
	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/x", nil)
	req.Host = "web.local"
	h.ServeHTTP(rr, req)
	if rr.Code != http.StatusAccepted {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	if rr.Header().Get("X-PocketHost-Upstream") == "" {
		t.Fatalf("missing upstream header")
	}
}

func TestProxyDashboardForUnmatchedHost(t *testing.T) {
	h := newProxyHandler(
		"127.0.0.1:0",
		time.Now(),
		map[string]string{
			"web.local":   "http://127.0.0.1:8080",
			"files.local": "http://127.0.0.1:8090",
		},
		discardLogger{},
	)
	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Host = "127.0.0.1:8088"
	h.ServeHTTP(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	if got := rr.Header().Get("Content-Type"); got != "text/html; charset=utf-8" {
		t.Fatalf("content type=%q", got)
	}
	body := rr.Body.String()
	for _, want := range []string{"PocketHost proxy", "/go/web.local/", "files.local", "http://127.0.0.1:8080"} {
		if !strings.Contains(body, want) {
			t.Fatalf("dashboard missing %q in %s", want, body)
		}
	}
}

func TestProxyDashboardRouteForwardsThroughLocalPath(t *testing.T) {
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/hello" {
			t.Fatalf("path=%q", r.URL.Path)
		}
		if got := r.Header.Get("X-PocketHost-Forwarded-Host"); got != "web.local" {
			t.Fatalf("forwarded host=%q", got)
		}
		_, _ = w.Write([]byte("dashboard route ok"))
	}))
	defer backend.Close()

	h := newProxyHandler("127.0.0.1:0", time.Now(), map[string]string{"web.local": backend.URL}, discardLogger{})
	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/go/web.local/hello", nil)
	req.Host = "127.0.0.1:8088"
	h.ServeHTTP(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	if !strings.Contains(rr.Body.String(), "dashboard route ok") {
		t.Fatalf("unexpected body=%s", rr.Body.String())
	}
}

func TestProxyReturnsBadGatewayForUpstreamError(t *testing.T) {
	h := newProxyHandler("127.0.0.1:0", time.Now(), map[string]string{"web.local": "http://127.0.0.1:1"}, discardLogger{})
	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Host = "web.local"
	h.ServeHTTP(rr, req)
	if rr.Code != http.StatusBadGateway {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
}

type discardLogger struct{}

func (discardLogger) Printf(string, ...any) {}
