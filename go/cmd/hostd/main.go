package main

import (
	"embed"
	"flag"
	"io/fs"
	"net/http"
	"os"
	"runtime"
	"strconv"
	"time"

	"dev.pockethost/daemons/internal/pocket"
)

//go:embed web
var webAssets embed.FS

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
	return buildHandler(addr, started, token, defaultGateway(token))
}

// buildHandler wires the host control plane: an embedded web panel and a
// loopback gateway over the sibling daemons. The panel and /health are public;
// every /api/* route requires the admin token.
func buildHandler(addr string, started time.Time, token string, gw *gateway) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", pocket.HealthHandler("hostd", addr, started, hostExtra))
	mux.Handle("/api/", pocket.RequireToken(apiMux(gw), token))
	mux.Handle("/", staticHandler())
	return mux
}

func apiMux(gw *gateway) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/status", statusHandler)
	mux.HandleFunc("/api/services", gw.servicesHandler)
	mux.HandleFunc("/api/ddns/update-now", gw.ddnsUpdateHandler)
	mux.HandleFunc("/api/files", gw.filesListHandler)
	mux.HandleFunc("/api/files/download", gw.filesDownloadHandler)
	mux.HandleFunc("/api/files/delete", gw.filesDeleteHandler)
	mux.HandleFunc("/api/files/upload", gw.filesUploadHandler)
	return mux
}

func staticHandler() http.Handler {
	sub, err := fs.Sub(webAssets, "web")
	if err != nil {
		panic(err)
	}
	return http.FileServerFS(sub)
}

func statusHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		pocket.WriteError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	pocket.WriteJSON(w, http.StatusOK, map[string]any{
		"runtime": hostExtra(),
		"env": map[string]string{
			"HOME":   os.Getenv("HOME"),
			"TMPDIR": os.Getenv("TMPDIR"),
		},
	})
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
