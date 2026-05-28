package main

import (
	"bytes"
	"io"
	"log"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestDownloadRejectsSymlinkEscape(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	if err := os.WriteFile(filepath.Join(outside, "secret.txt"), []byte("secret"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, filepath.Join(root, "link")); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}

	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/files/link/secret.txt", nil)
	downloadHandler(root).ServeHTTP(rr, req)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("got status %d body=%s", rr.Code, rr.Body.String())
	}
	if strings.Contains(rr.Body.String(), "secret") {
		t.Fatalf("response leaked outside file contents: %q", rr.Body.String())
	}
}

func TestDownloadDisablesDirectoryListing(t *testing.T) {
	root := t.TempDir()
	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/files/", nil)
	downloadHandler(root).ServeHTTP(rr, req)
	if rr.Code != http.StatusForbidden {
		t.Fatalf("got status %d body=%s", rr.Code, rr.Body.String())
	}
}

func TestSanitizeUploadNameRejectsSeparators(t *testing.T) {
	bad := []string{"../evil.txt", "..\\evil.txt", "dir/file.txt", "", ".", ".."}
	for _, name := range bad {
		if got, err := sanitizeUploadName(name); err == nil {
			t.Fatalf("sanitizeUploadName(%q) unexpectedly returned %q", name, got)
		}
	}
}

func TestUploadRejectsExistingSymlink(t *testing.T) {
	root := t.TempDir()
	outside := filepath.Join(t.TempDir(), "outside.txt")
	if err := os.WriteFile(outside, []byte("keep"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, filepath.Join(root, "evil.txt")); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}

	body := &bytes.Buffer{}
	writer := multipart.NewWriter(body)
	part, err := writer.CreateFormFile("file", "evil.txt")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := io.WriteString(part, "evil"); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}

	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/upload", body)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	uploadHandler(root, 256<<20).ServeHTTP(rr, req)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("got status %d body=%s", rr.Code, rr.Body.String())
	}
	data, err := os.ReadFile(outside)
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "keep" {
		t.Fatalf("outside file was overwritten: %q", data)
	}
}

func TestDeleteRejectsSymlinkParentEscape(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	victim := filepath.Join(outside, "victim.txt")
	if err := os.WriteFile(victim, []byte("keep"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, filepath.Join(root, "link")); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}

	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodDelete, "/api/delete?path=link/victim.txt", nil)
	deleteHandler(root).ServeHTTP(rr, req)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("got status %d body=%s", rr.Code, rr.Body.String())
	}
	if _, err := os.Stat(victim); err != nil {
		t.Fatalf("outside file was removed: %v", err)
	}
}

func TestNewHandlerRequiresTokenExceptHealth(t *testing.T) {
	root := t.TempDir()
	handler := newHandler(root, "127.0.0.1:0", time.Now(), "secret", 256<<20, log.New(io.Discard, "", 0))

	health := httptest.NewRecorder()
	handler.ServeHTTP(health, httptest.NewRequest(http.MethodGet, "/health", nil))
	if health.Code != http.StatusOK {
		t.Fatalf("health got status %d", health.Code)
	}

	files := httptest.NewRecorder()
	handler.ServeHTTP(files, httptest.NewRequest(http.MethodGet, "/api/files", nil))
	if files.Code != http.StatusUnauthorized {
		t.Fatalf("files got status %d", files.Code)
	}
}

func TestUploadRespectsMaxBytes(t *testing.T) {
	root := t.TempDir()
	body := &bytes.Buffer{}
	writer := multipart.NewWriter(body)
	part, err := writer.CreateFormFile("file", "large.txt")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := io.WriteString(part, strings.Repeat("x", 128)); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}

	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/upload", body)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	uploadHandler(root, 64).ServeHTTP(rr, req)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("got status %d body=%s", rr.Code, rr.Body.String())
	}
	if _, err := os.Stat(filepath.Join(root, "large.txt")); !os.IsNotExist(err) {
		t.Fatalf("large upload created file; stat err=%v", err)
	}
}

func TestUploadCommitsAtomically(t *testing.T) {
	root := t.TempDir()
	body := &bytes.Buffer{}
	writer := multipart.NewWriter(body)
	part, err := writer.CreateFormFile("file", "ok.txt")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := io.WriteString(part, "ok"); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}

	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/upload", body)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	uploadHandler(root, 256<<20).ServeHTTP(rr, req)
	if rr.Code != http.StatusCreated {
		t.Fatalf("got status %d body=%s", rr.Code, rr.Body.String())
	}
	data, err := os.ReadFile(filepath.Join(root, "ok.txt"))
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "ok" {
		t.Fatalf("got %q", data)
	}
}
