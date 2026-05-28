package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestPublicIPFromRejectsPrivateIP(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("192.168.1.10"))
	}))
	defer srv.Close()
	if _, err := publicIPFrom(srv.Client(), srv.URL); err == nil {
		t.Fatalf("private IP unexpectedly accepted")
	}
}

func TestValidateRecordTypeMatchesIPVersion(t *testing.T) {
	if err := validateRecordType("A", "8.8.8.8"); err != nil {
		t.Fatalf("A IPv4 rejected: %v", err)
	}
	if err := validateRecordType("AAAA", "2001:4860:4860::8888"); err != nil {
		t.Fatalf("AAAA IPv6 rejected: %v", err)
	}
	if err := validateRecordType("A", "2001:4860:4860::8888"); err == nil {
		t.Fatalf("A IPv6 unexpectedly accepted")
	}
	if err := validateRecordType("CNAME", "8.8.8.8"); err == nil {
		t.Fatalf("CNAME unexpectedly accepted")
	}
}

func TestUpdateOncePatchesCloudflare(t *testing.T) {
	var patched bool
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/ip":
			_, _ = w.Write([]byte("8.8.8.8"))
		case "/zones/zone/dns_records/record":
			if r.Method != http.MethodPatch {
				t.Fatalf("method=%s", r.Method)
			}
			if got := r.Header.Get("Authorization"); got != "Bearer cf-token" {
				t.Fatalf("authorization=%q", got)
			}
			var body map[string]any
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Fatal(err)
			}
			if body["content"] != "8.8.8.8" || body["type"] != "A" {
				t.Fatalf("body=%#v", body)
			}
			patched = true
			_, _ = w.Write([]byte(`{"success":true}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	st := newState()
	cfg := ddnsConfig{
		Token:      "cf-token",
		ZoneID:     "zone",
		RecordID:   "record",
		RecordName: "host.example.com",
		RecordType: "A",
		IPEndpoint: srv.URL + "/ip",
		APIBaseURL: srv.URL,
		HTTPClient: srv.Client(),
	}
	if err := updateOnce(cfg, st); err != nil {
		t.Fatalf("updateOnce failed: %v", err)
	}
	if !patched || valueString(st.LastStatus.Load()) != "updated" {
		t.Fatalf("patched=%v status=%q", patched, valueString(st.LastStatus.Load()))
	}
}

func TestDDNSHandlerRequiresTokenForUpdate(t *testing.T) {
	st := newState()
	h := newHandler("127.0.0.1:0", time.Now(), ddnsConfig{}, st, "admin")
	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "/api/update-now", strings.NewReader("")))
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("got status %d", rr.Code)
	}

	ok := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/update-now", strings.NewReader(""))
	req.Header.Set("Authorization", "Bearer admin")
	h.ServeHTTP(ok, req)
	if ok.Code != http.StatusOK {
		t.Fatalf("authorized request got status %d body=%s", ok.Code, ok.Body.String())
	}
}

func TestUpdateOnceSkipsUnchangedIPAfterSuccess(t *testing.T) {
	patches := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/ip":
			_, _ = w.Write([]byte("8.8.4.4"))
		case "/zones/zone/dns_records/record":
			patches++
			_, _ = w.Write([]byte(`{"success":true}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	st := newState()
	cfg := ddnsConfig{
		Token:      "cf-token",
		ZoneID:     "zone",
		RecordID:   "record",
		RecordName: "host.example.com",
		RecordType: "A",
		IPEndpoint: srv.URL + "/ip",
		APIBaseURL: srv.URL,
		HTTPClient: srv.Client(),
	}
	if err := updateOnce(cfg, st); err != nil {
		t.Fatalf("first update failed: %v", err)
	}
	if err := updateOnce(cfg, st); err != nil {
		t.Fatalf("second update failed: %v", err)
	}
	if patches != 1 {
		t.Fatalf("patches=%d", patches)
	}
	if valueString(st.LastStatus.Load()) != "unchanged" {
		t.Fatalf("status=%q", valueString(st.LastStatus.Load()))
	}
}
