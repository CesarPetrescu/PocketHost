package pocket

import (
	"context"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestSafeJoinRejectsTraversal(t *testing.T) {
	root := t.TempDir()
	bad := []string{"..", "../x", "a/../../x", "/../../etc/passwd"}
	for _, input := range bad {
		if _, err := SafeJoin(root, input); err == nil {
			t.Fatalf("SafeJoin(%q) unexpectedly succeeded", input)
		}
	}
}

func TestSafeJoinAcceptsRelativePath(t *testing.T) {
	root := t.TempDir()
	got, err := SafeJoin(root, "a/b.txt")
	if err != nil {
		t.Fatalf("SafeJoin returned error: %v", err)
	}
	want := filepath.Join(root, "a", "b.txt")
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
}

func TestSafeExistingPathRejectsSymlinkEscape(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	if err := os.WriteFile(filepath.Join(outside, "secret.txt"), []byte("secret"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, filepath.Join(root, "link")); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	if _, err := SafeExistingPath(root, "link/secret.txt"); err == nil {
		t.Fatalf("SafeExistingPath followed a symlink outside the root")
	}
}

func TestSafeExistingPathAcceptsNormalExistingFile(t *testing.T) {
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, "a"), 0o750); err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(root, "a", "b.txt")
	if err := os.WriteFile(want, []byte("ok"), 0o640); err != nil {
		t.Fatal(err)
	}
	got, err := SafeExistingPath(root, "a/b.txt")
	if err != nil {
		t.Fatalf("SafeExistingPath returned error: %v", err)
	}
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
}

func TestRequireTokenAllowsHealth(t *testing.T) {
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusNoContent) })
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rr := httptest.NewRecorder()
	RequireToken(next, "secret").ServeHTTP(rr, req)
	if rr.Code != http.StatusNoContent {
		t.Fatalf("got status %d", rr.Code)
	}
}

func TestRequireTokenBlocksWithoutToken(t *testing.T) {
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusNoContent) })
	req := httptest.NewRequest(http.MethodGet, "/api/files", nil)
	rr := httptest.NewRecorder()
	RequireToken(next, "secret").ServeHTTP(rr, req)
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("got status %d", rr.Code)
	}
}

func TestRequireTokenAcceptsBearer(t *testing.T) {
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusNoContent) })
	req := httptest.NewRequest(http.MethodGet, "/api/files", nil)
	req.Header.Set("Authorization", "Bearer secret")
	rr := httptest.NewRecorder()
	RequireToken(next, "secret").ServeHTTP(rr, req)
	if rr.Code != http.StatusNoContent {
		t.Fatalf("got status %d", rr.Code)
	}
}

func TestServeGracefullyStopsOnContextCancel(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		WriteJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})
	srv := NewHTTPServer("127.0.0.1:0", mux)
	done := make(chan error, 1)
	go func() {
		done <- ServeGracefully(ctx, srv, ln, log.New(io.Discard, "", 0))
	}()

	client := &http.Client{Timeout: 2 * time.Second}
	resp, err := client.Get("http://" + ln.Addr().String() + "/health")
	if err != nil {
		t.Fatalf("health request failed: %v", err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("got status %d", resp.StatusCode)
	}

	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("server returned error: %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatalf("server did not shut down")
	}
}

func TestRequireTokenAcceptsHeaderTokenConstantTime(t *testing.T) {
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusNoContent) })
	req := httptest.NewRequest(http.MethodGet, "/api/files", nil)
	req.Header.Set("X-PocketHost-Token", "secret")
	rr := httptest.NewRecorder()
	RequireToken(next, "secret").ServeHTTP(rr, req)
	if rr.Code != http.StatusNoContent {
		t.Fatalf("got status %d", rr.Code)
	}
}

func TestBearerTokenRejectsMalformedAuthorization(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("Authorization", "Bearer")
	if got := BearerToken(req); got != "" {
		t.Fatalf("got %q", got)
	}
}

func TestConstantTimeTokenEqual(t *testing.T) {
	if !ConstantTimeTokenEqual("abc", "abc") {
		t.Fatalf("equal tokens rejected")
	}
	if ConstantTimeTokenEqual("abc", "abcd") || ConstantTimeTokenEqual("abc", "") {
		t.Fatalf("invalid token accepted")
	}
}

func TestValidateListenAddrDefaultsToLoopback(t *testing.T) {
	good := []string{"127.0.0.1:8080", "localhost:8080", "[::1]:8080"}
	for _, addr := range good {
		if err := ValidateListenAddr(addr, false); err != nil {
			t.Fatalf("ValidateListenAddr(%q) failed: %v", addr, err)
		}
	}
	bad := []string{"0.0.0.0:8080", ":8080", "192.168.1.5:8080", "127.0.0.1:99999"}
	for _, addr := range bad {
		if err := ValidateListenAddr(addr, false); err == nil {
			t.Fatalf("ValidateListenAddr(%q) unexpectedly succeeded", addr)
		}
	}
	if err := ValidateListenAddr("0.0.0.0:8080", true); err != nil {
		t.Fatalf("public bind override rejected: %v", err)
	}
}

func TestRequestLogRecordsStatusAndRequestID(t *testing.T) {
	var got string
	logger := log.New(logWriterFunc(func(p []byte) (int, error) {
		got += string(p)
		return len(p), nil
	}), "", 0)
	wrapped := RequestLog(logger, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		WriteError(w, http.StatusTeapot, "teapot")
	}))
	rr := httptest.NewRecorder()
	wrapped.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "/tea?x=1", nil))
	if rr.Header().Get("X-Request-ID") == "" {
		t.Fatalf("missing request id header")
	}
	if !strings.Contains(got, "status=418") || !strings.Contains(got, "path=\"/tea?x=1\"") {
		t.Fatalf("unexpected log line: %q", got)
	}
}

type logWriterFunc func([]byte) (int, error)

func (f logWriterFunc) Write(p []byte) (int, error) { return f(p) }
