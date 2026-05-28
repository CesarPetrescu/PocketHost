package main

import (
	"flag"
	"net/http"
	"os"
	"runtime"
	"strconv"
	"time"

	"dev.pockethost/daemons/internal/pocket"
)

func main() {
	var addr string
	flag.StringVar(&addr, "addr", pocket.Env("POCKETHOST_HOSTD_ADDR", "127.0.0.1:8099"), "listen address")
	flag.Parse()

	log := pocket.NewLogger("hostd")
	started := time.Now()
	token := os.Getenv("POCKETHOST_TOKEN")
	handler := newHandler(addr, started, token)

	if err := pocket.ListenAndServeGracefully(addr, handler, log); err != nil && err != http.ErrServerClosed {
		log.Fatalf("server stopped: %v", err)
	}
}

func newHandler(addr string, started time.Time, token string) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", pocket.HealthHandler("hostd", addr, started, hostExtra))
	mux.HandleFunc("/api/status", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			pocket.WriteJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
			return
		}
		pocket.WriteJSON(w, http.StatusOK, map[string]any{
			"runtime": hostExtra(),
			"env": map[string]string{
				"HOME":   os.Getenv("HOME"),
				"TMPDIR": os.Getenv("TMPDIR"),
			},
		})
	})
	return pocket.RequireToken(mux, token)
}

func hostExtra() map[string]string {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	return map[string]string{
		"goos":        runtime.GOOS,
		"goarch":      runtime.GOARCH,
		"goroutines":  strconv.Itoa(runtime.NumGoroutine()),
		"alloc_bytes": strconv.FormatUint(m.Alloc, 10),
		"sys_bytes":   strconv.FormatUint(m.Sys, 10),
	}
}
