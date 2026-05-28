package main

import (
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestStaticHandlerServesIndex(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "index.html"), []byte("hello"), 0o640); err != nil {
		t.Fatal(err)
	}
	handler := newHandler(root, "127.0.0.1:0", time.Now(), log.New(io.Discard, "", 0))
	rr := httptest.NewRecorder()
	handler.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "/", nil))
	if rr.Code != http.StatusOK || !strings.Contains(rr.Body.String(), "hello") {
		t.Fatalf("status=%d body=%q", rr.Code, rr.Body.String())
	}
}

func TestStaticHandlerRejectsTraversal(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	if err := os.WriteFile(filepath.Join(outside, "secret.txt"), []byte("secret"), 0o600); err != nil {
		t.Fatal(err)
	}
	rr := httptest.NewRecorder()
	staticHandler(root).ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "/../secret.txt", nil))
	if rr.Code != http.StatusNotFound {
		t.Fatalf("status=%d body=%q", rr.Code, rr.Body.String())
	}
	if strings.Contains(rr.Body.String(), "secret") {
		t.Fatalf("leaked secret in response: %q", rr.Body.String())
	}
}

func TestStaticHandlerRejectsSymlinkEscape(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	if err := os.WriteFile(filepath.Join(outside, "secret.txt"), []byte("secret"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, filepath.Join(root, "link")); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	rr := httptest.NewRecorder()
	staticHandler(root).ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "/link/secret.txt", nil))
	if rr.Code != http.StatusNotFound {
		t.Fatalf("status=%d body=%q", rr.Code, rr.Body.String())
	}
}

func TestStaticHandlerDisablesDirectoryListing(t *testing.T) {
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, "dir"), 0o750); err != nil {
		t.Fatal(err)
	}
	rr := httptest.NewRecorder()
	staticHandler(root).ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "/dir/", nil))
	if rr.Code != http.StatusForbidden {
		t.Fatalf("status=%d body=%q", rr.Code, rr.Body.String())
	}
}
