package pocket

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const Version = "0.1.2"

type Health struct {
	Service       string            `json:"service"`
	Status        string            `json:"status"`
	Version       string            `json:"version"`
	Addr          string            `json:"addr,omitempty"`
	UptimeSeconds int64             `json:"uptime_seconds"`
	TimeUTC       string            `json:"time_utc"`
	Extra         map[string]string `json:"extra,omitempty"`
}

func NewLogger(service string) *log.Logger {
	return log.New(os.Stdout, fmt.Sprintf("%s ", strings.ToUpper(service)), log.LstdFlags|log.Lmicroseconds|log.LUTC)
}

func WriteJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	_ = enc.Encode(v)
}

func WriteError(w http.ResponseWriter, code int, message string) {
	WriteJSON(w, code, map[string]string{"error": message})
}

func HealthHandler(service, addr string, started time.Time, extra func() map[string]string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		data := map[string]string(nil)
		if extra != nil {
			data = extra()
		}
		WriteJSON(w, http.StatusOK, Health{
			Service:       service,
			Status:        "ok",
			Version:       Version,
			Addr:          addr,
			UptimeSeconds: int64(time.Since(started).Seconds()),
			TimeUTC:       time.Now().UTC().Format(time.RFC3339),
			Extra:         data,
		})
	}
}

func EnsureDir(path string) error {
	if path == "" {
		return errors.New("empty path")
	}
	return os.MkdirAll(path, 0o750)
}

func SafeJoin(root, requested string) (string, error) {
	if root == "" {
		return "", errors.New("empty root")
	}
	if filepath.IsAbs(requested) {
		return "", fmt.Errorf("absolute paths are not allowed")
	}
	clean := filepath.Clean(requested)
	if clean == "." || clean == string(filepath.Separator) {
		return filepath.Clean(root), nil
	}
	if clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
		return "", fmt.Errorf("path escapes root")
	}
	full := filepath.Join(root, clean)
	rel, err := filepath.Rel(root, full)
	if err != nil {
		return "", err
	}
	if rel == ".." || strings.HasPrefix(rel, "../") || filepath.IsAbs(rel) {
		return "", fmt.Errorf("path escapes root")
	}
	return full, nil
}

// SafeExistingPath returns an existing path below root and rejects symlink
// components. This is intentionally stricter than SafeJoin because file-server
// read/delete operations must not follow user-created symlinks outside the
// configured data directory.
func SafeExistingPath(root, requested string) (string, error) {
	full, err := SafeJoin(root, requested)
	if err != nil {
		return "", err
	}
	rootAbs, err := filepath.Abs(filepath.Clean(root))
	if err != nil {
		return "", err
	}
	fullAbs, err := filepath.Abs(filepath.Clean(full))
	if err != nil {
		return "", err
	}
	rel, err := filepath.Rel(rootAbs, fullAbs)
	if err != nil {
		return "", err
	}
	if rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) || filepath.IsAbs(rel) {
		return "", fmt.Errorf("path escapes root")
	}
	if _, err := os.Stat(fullAbs); err != nil {
		return "", err
	}
	if rel == "." {
		return rootAbs, nil
	}
	cursor := rootAbs
	for _, part := range strings.Split(rel, string(filepath.Separator)) {
		if part == "" || part == "." {
			continue
		}
		cursor = filepath.Join(cursor, part)
		info, err := os.Lstat(cursor)
		if err != nil {
			return "", err
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return "", fmt.Errorf("path contains symlink: %s", part)
		}
	}
	return fullAbs, nil
}

func ResolveListenAddr(addr string) (string, error) {
	allowPublic := strings.EqualFold(os.Getenv("POCKETHOST_ALLOW_PUBLIC_BIND"), "true")
	if err := ValidateListenAddr(addr, allowPublic); err != nil {
		return "", err
	}
	return addr, nil
}

func ValidateListenAddr(addr string, allowPublic bool) error {
	if strings.TrimSpace(addr) == "" {
		return fmt.Errorf("listen address is required")
	}
	host, portText, err := net.SplitHostPort(addr)
	if err != nil {
		return fmt.Errorf("listen address must be host:port: %w", err)
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 1 || port > 65535 {
		return fmt.Errorf("listen port must be between 1 and 65535")
	}
	if allowPublic {
		return nil
	}
	host = strings.Trim(host, "[]")
	if strings.EqualFold(host, "localhost") {
		return nil
	}
	ip := net.ParseIP(host)
	if ip != nil && ip.IsLoopback() {
		return nil
	}
	return fmt.Errorf("refusing public listen address %q; set POCKETHOST_ALLOW_PUBLIC_BIND=true to override", addr)
}

func NewHTTPServer(addr string, handler http.Handler) *http.Server {
	return &http.Server{
		Addr:              addr,
		Handler:           SecurityHeaders(handler),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
}

func ListenAndServeGracefully(addr string, handler http.Handler, logger *log.Logger) error {
	if resolved, err := ResolveListenAddr(addr); err != nil {
		return err
	} else {
		addr = resolved
	}
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	return ServeGracefully(ctx, NewHTTPServer(addr, handler), ln, logger)
}

func ServeGracefully(ctx context.Context, srv *http.Server, ln net.Listener, logger *log.Logger) error {
	if logger != nil {
		logger.Printf("listening addr=%s", ln.Addr().String())
	}
	errCh := make(chan error, 1)
	go func() {
		errCh <- srv.Serve(ln)
	}()

	select {
	case err := <-errCh:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	case <-ctx.Done():
		if logger != nil {
			logger.Printf("shutdown requested")
		}
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := srv.Shutdown(shutdownCtx); err != nil {
			_ = srv.Close()
			return err
		}
		err := <-errCh
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	}
}

func SecurityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("X-Frame-Options", "DENY")
		next.ServeHTTP(w, r)
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
	bytes  int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

func (r *statusRecorder) Write(p []byte) (int, error) {
	if r.status == 0 {
		r.status = http.StatusOK
	}
	n, err := r.ResponseWriter.Write(p)
	r.bytes += n
	return n, err
}

func RequestLog(logger interface{ Printf(string, ...any) }, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		reqID := r.Header.Get("X-Request-ID")
		if strings.TrimSpace(reqID) == "" {
			reqID = strconv.FormatInt(time.Now().UnixNano(), 36)
		}
		w.Header().Set("X-Request-ID", reqID)
		next.ServeHTTP(rec, r)
		logger.Printf("request_id=%s method=%s path=%q status=%d bytes=%d remote=%s duration_ms=%d", reqID, r.Method, r.URL.RequestURI(), rec.status, rec.bytes, r.RemoteAddr, time.Since(start).Milliseconds())
	})
}

func ConstantTimeTokenEqual(expected, got string) bool {
	if expected == "" || got == "" {
		return false
	}
	expectedHash := sha256.Sum256([]byte(expected))
	gotHash := sha256.Sum256([]byte(got))
	return subtle.ConstantTimeCompare(expectedHash[:], gotHash[:]) == 1 && len(expected) == len(got)
}

func BearerToken(r *http.Request) string {
	if got := strings.TrimSpace(r.Header.Get("X-PocketHost-Token")); got != "" {
		return got
	}
	auth := strings.TrimSpace(r.Header.Get("Authorization"))
	fields := strings.Fields(auth)
	if len(fields) == 2 && strings.EqualFold(fields[0], "Bearer") {
		return fields[1]
	}
	return ""
}

func RequireToken(next http.Handler, token string) http.Handler {
	if token == "" {
		return next
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/health" {
			next.ServeHTTP(w, r)
			return
		}
		if !ConstantTimeTokenEqual(token, BearerToken(r)) {
			WriteError(w, http.StatusUnauthorized, "missing or invalid token")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func Env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
