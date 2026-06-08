package main

import "testing"

func TestRequestHostname(t *testing.T) {
	cases := map[string]string{
		"example.trycloudflare.com":        "example.trycloudflare.com",
		"Example.TryCloudflare.Com:443":    "example.trycloudflare.com",
		"[::1]:8092":                       "::1",
		"  more-words.trycloudflare.com  ": "more-words.trycloudflare.com",
	}
	for input, want := range cases {
		if got := requestHostname(input); got != want {
			t.Fatalf("requestHostname(%q)=%q want %q", input, got, want)
		}
	}
}

func TestIsQuickTunnelHost(t *testing.T) {
	if !isQuickTunnelHost("demo.trycloudflare.com") {
		t.Fatal("expected trycloudflare host to match")
	}
	if isQuickTunnelHost("trycloudflare.com") {
		t.Fatal("apex trycloudflare.com should not match")
	}
	if isQuickTunnelHost("example.com") {
		t.Fatal("unrelated host should not match")
	}
}
