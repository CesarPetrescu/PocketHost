package main

import (
	"flag"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"dev.pockethost/daemons/internal/pocket"
)

func main() {
	var addr string
	var dataDir string
	flag.StringVar(&addr, "addr", pocket.Env("POCKETHOST_WEBD_ADDR", "127.0.0.1:8080"), "listen address")
	flag.StringVar(&dataDir, "data-dir", pocket.Env("POCKETHOST_WEBD_DATA", "./public/www"), "web root")
	flag.Parse()

	log := pocket.NewLogger("webd")
	started := time.Now()

	if err := pocket.EnsureDir(dataDir); err != nil {
		log.Fatalf("create data dir: %v", err)
	}
	index := filepath.Join(dataDir, "index.html")
	if _, err := os.Stat(index); os.IsNotExist(err) {
		_ = os.WriteFile(index, []byte(defaultIndex()), 0o640)
	}

	handler := newHandler(dataDir, addr, started, log)
	if err := pocket.ListenAndServeGracefully(addr, handler, log); err != nil && err != http.ErrServerClosed {
		log.Fatalf("server stopped: %v", err)
	}
}

func newHandler(dataDir, addr string, started time.Time, log interface{ Printf(string, ...any) }) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", pocket.HealthHandler("webd", addr, started, func() map[string]string {
		return map[string]string{"data_dir": dataDir}
	}))
	mux.HandleFunc("/api/time", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			pocket.WriteError(w, http.StatusMethodNotAllowed, "method not allowed")
			return
		}
		pocket.WriteJSON(w, http.StatusOK, map[string]string{"time_utc": time.Now().UTC().Format(time.RFC3339)})
	})
	mux.HandleFunc("/", staticHandler(dataDir))
	return pocket.RequestLog(log, mux)
}

func staticHandler(root string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			pocket.WriteError(w, http.StatusMethodNotAllowed, "method not allowed")
			return
		}
		rel := strings.TrimPrefix(r.URL.Path, "/")
		if rel == "" {
			rel = "index.html"
		}
		full, err := pocket.SafeExistingPath(root, rel)
		if err != nil {
			pocket.WriteError(w, http.StatusNotFound, "not found")
			return
		}
		info, err := os.Stat(full)
		if err != nil {
			pocket.WriteError(w, http.StatusNotFound, "not found")
			return
		}
		if info.IsDir() {
			index := filepath.Join(full, "index.html")
			relIndex, relErr := filepath.Rel(filepath.Clean(root), index)
			if relErr == nil {
				if safeIndex, err := pocket.SafeExistingPath(root, filepath.ToSlash(relIndex)); err == nil {
					http.ServeFile(w, r, safeIndex)
					return
				}
			}
			pocket.WriteError(w, http.StatusForbidden, "directory listing disabled")
			return
		}
		http.ServeFile(w, r, full)
	}
}

func defaultIndex() string {
	return fmt.Sprintf(`<!doctype html>
<html>
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>PocketHost webd</title>
  <style>body{font-family:system-ui,sans-serif;margin:2rem;max-width:760px;line-height:1.5}</style>
</head>
<body>
  <h1>PocketHost webd</h1>
  <p>This page is served by the Go web daemon running inside the Android supervisor.</p>
  <p>Health: <a href="/health">/health</a></p>
  <p>UTC: %s</p>
</body>
</html>
`, time.Now().UTC().Format(time.RFC3339))
}
