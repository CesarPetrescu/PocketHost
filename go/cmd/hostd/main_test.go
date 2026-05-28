package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestHostStatusRequiresTokenButHealthDoesNot(t *testing.T) {
	h := newHandler("127.0.0.1:0", time.Now(), "secret")

	health := httptest.NewRecorder()
	h.ServeHTTP(health, httptest.NewRequest(http.MethodGet, "/health", nil))
	if health.Code != http.StatusOK {
		t.Fatalf("health status=%d", health.Code)
	}

	status := httptest.NewRecorder()
	h.ServeHTTP(status, httptest.NewRequest(http.MethodGet, "/api/status", nil))
	if status.Code != http.StatusUnauthorized {
		t.Fatalf("status without token=%d", status.Code)
	}

	req := httptest.NewRequest(http.MethodGet, "/api/status", nil)
	req.Header.Set("X-PocketHost-Token", "secret")
	authed := httptest.NewRecorder()
	h.ServeHTTP(authed, req)
	if authed.Code != http.StatusOK {
		t.Fatalf("status with token=%d body=%s", authed.Code, authed.Body.String())
	}
}
