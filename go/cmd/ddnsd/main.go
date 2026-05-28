package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"sync/atomic"
	"time"

	"dev.pockethost/daemons/internal/pocket"
)

type state struct {
	LastIP     atomic.Value
	LastStatus atomic.Value
	LastUpdate atomic.Value
}

type ddnsConfig struct {
	Token      string
	ZoneID     string
	RecordID   string
	RecordName string
	RecordType string
	Proxied    bool
	IPEndpoint string
	APIBaseURL string
	HTTPClient *http.Client
}

func main() {
	var addr, intervalText string
	var cfg ddnsConfig
	flag.StringVar(&addr, "addr", pocket.Env("POCKETHOST_DDNSD_ADDR", "127.0.0.1:8091"), "status listen address")
	flag.StringVar(&intervalText, "interval", pocket.Env("POCKETHOST_DDNS_INTERVAL", "15m"), "update interval")
	flag.StringVar(&cfg.ZoneID, "cloudflare-zone-id", os.Getenv("CLOUDFLARE_ZONE_ID"), "Cloudflare zone ID")
	flag.StringVar(&cfg.RecordID, "cloudflare-record-id", os.Getenv("CLOUDFLARE_RECORD_ID"), "Cloudflare DNS record ID")
	flag.StringVar(&cfg.RecordName, "cloudflare-record-name", os.Getenv("CLOUDFLARE_RECORD_NAME"), "Cloudflare DNS record name")
	flag.StringVar(&cfg.RecordType, "record-type", pocket.Env("CLOUDFLARE_RECORD_TYPE", "A"), "DNS record type: A or AAAA")
	flag.StringVar(&cfg.IPEndpoint, "ip-url", pocket.Env("POCKETHOST_DDNS_IP_URL", "https://api.ipify.org"), "public IP discovery URL")
	flag.StringVar(&cfg.APIBaseURL, "cloudflare-api-base-url", pocket.Env("CLOUDFLARE_API_BASE_URL", "https://api.cloudflare.com/client/v4"), "Cloudflare API base URL")
	flag.BoolVar(&cfg.Proxied, "proxied", os.Getenv("CLOUDFLARE_PROXIED") == "true", "Cloudflare proxy mode")
	flag.Parse()

	cfg.Token = os.Getenv("CLOUDFLARE_API_TOKEN")
	cfg.HTTPClient = &http.Client{Timeout: 20 * time.Second}
	adminToken := os.Getenv("POCKETHOST_TOKEN")
	interval, err := time.ParseDuration(intervalText)
	if err != nil || interval < time.Minute {
		interval = 15 * time.Minute
	}

	log := pocket.NewLogger("ddnsd")
	started := time.Now()
	st := newState()

	go updateLoop(interval, cfg, st, log)

	handler := newHandler(addr, started, cfg, st, adminToken)
	if err := pocket.ListenAndServeGracefully(addr, handler, log); err != nil && err != http.ErrServerClosed {
		log.Fatalf("server stopped: %v", err)
	}
}

func newState() *state {
	st := &state{}
	st.LastStatus.Store("idle")
	st.LastIP.Store("")
	st.LastUpdate.Store("")
	return st
}

func updateLoop(interval time.Duration, cfg ddnsConfig, st *state, log interface{ Printf(string, ...any) }) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		if err := updateOnce(cfg, st); err != nil {
			st.LastStatus.Store("error: " + err.Error())
			log.Printf("update error=%v", err)
		}
		<-ticker.C
	}
}

func newHandler(addr string, started time.Time, cfg ddnsConfig, st *state, adminToken string) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", pocket.HealthHandler("ddnsd", addr, started, func() map[string]string {
		return map[string]string{
			"last_ip":     valueString(st.LastIP.Load()),
			"last_status": valueString(st.LastStatus.Load()),
			"last_update": valueString(st.LastUpdate.Load()),
			"configured":  boolString(cfg.configured()),
			"record_type": strings.ToUpper(cfg.RecordType),
		}
	}))
	mux.HandleFunc("/api/update-now", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			pocket.WriteJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
			return
		}
		if err := updateOnce(cfg, st); err != nil {
			pocket.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		pocket.WriteJSON(w, http.StatusOK, map[string]string{"status": valueString(st.LastStatus.Load()), "ip": valueString(st.LastIP.Load())})
	})
	return pocket.RequireToken(mux, adminToken)
}

func (cfg ddnsConfig) configured() bool {
	return cfg.Token != "" && cfg.ZoneID != "" && cfg.RecordID != "" && cfg.RecordName != ""
}

func updateOnce(cfg ddnsConfig, st *state) error {
	if cfg.HTTPClient == nil {
		cfg.HTTPClient = &http.Client{Timeout: 20 * time.Second}
	}
	if cfg.APIBaseURL == "" {
		cfg.APIBaseURL = "https://api.cloudflare.com/client/v4"
	}
	if cfg.IPEndpoint == "" {
		cfg.IPEndpoint = "https://api.ipify.org"
	}
	if !cfg.configured() {
		st.LastStatus.Store("not configured")
		return nil
	}
	ip, err := publicIPFrom(cfg.HTTPClient, cfg.IPEndpoint)
	if err != nil {
		return err
	}
	recordType := strings.ToUpper(strings.TrimSpace(cfg.RecordType))
	if err := validateRecordType(recordType, ip); err != nil {
		return err
	}
	lastStatus := valueString(st.LastStatus.Load())
	if valueString(st.LastIP.Load()) == ip && (lastStatus == "updated" || lastStatus == "unchanged") {
		st.LastStatus.Store("unchanged")
		return nil
	}
	st.LastIP.Store(ip)
	payload := map[string]any{"type": recordType, "name": cfg.RecordName, "content": ip, "ttl": 1, "proxied": cfg.Proxied}
	raw, _ := json.Marshal(payload)
	endpoint := fmt.Sprintf("%s/zones/%s/dns_records/%s", strings.TrimRight(cfg.APIBaseURL, "/"), cfg.ZoneID, cfg.RecordID)
	req, err := http.NewRequest(http.MethodPatch, endpoint, bytes.NewReader(raw))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+cfg.Token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := cfg.HTTPClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("cloudflare status=%s body=%s", resp.Status, string(body))
	}
	st.LastStatus.Store("updated")
	st.LastUpdate.Store(time.Now().UTC().Format(time.RFC3339))
	return nil
}

func publicIPFrom(client *http.Client, endpoint string) (string, error) {
	if client == nil {
		client = &http.Client{Timeout: 10 * time.Second}
	}
	resp, err := client.Get(endpoint)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return "", fmt.Errorf("ip endpoint status=%s", resp.Status)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 128))
	if err != nil {
		return "", err
	}
	ip := strings.TrimSpace(string(body))
	parsed := net.ParseIP(ip)
	if parsed == nil || !isPublicRoutable(parsed) {
		return "", fmt.Errorf("invalid public ip response")
	}
	return ip, nil
}

func isPublicRoutable(ip net.IP) bool {
	if ip == nil {
		return false
	}
	return !(ip.IsLoopback() || ip.IsPrivate() || ip.IsUnspecified() || ip.IsMulticast() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast())
}

func validateRecordType(recordType, ipText string) error {
	ip := net.ParseIP(ipText)
	if ip == nil {
		return fmt.Errorf("invalid IP")
	}
	switch recordType {
	case "A":
		if ip.To4() == nil {
			return fmt.Errorf("A record requires IPv4 content")
		}
	case "AAAA":
		if ip.To4() != nil {
			return fmt.Errorf("AAAA record requires IPv6 content")
		}
	default:
		return fmt.Errorf("record type must be A or AAAA")
	}
	return nil
}

func valueString(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	return ""
}

func boolString(v bool) string {
	if v {
		return "true"
	}
	return "false"
}
