package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"dev.pockethost/daemons/internal/pocket"
)

type fileItem struct {
	Name    string `json:"name"`
	Path    string `json:"path"`
	IsDir   bool   `json:"is_dir"`
	Size    int64  `json:"size"`
	ModTime string `json:"mod_time"`
}

func main() {
	var addr string
	var dataDir string
	var maxUploadBytes int64
	flag.StringVar(&addr, "addr", pocket.Env("POCKETHOST_FILED_ADDR", "127.0.0.1:8090"), "listen address")
	flag.StringVar(&dataDir, "data-dir", pocket.Env("POCKETHOST_FILED_DATA", "./public/files"), "file root")
	flag.Int64Var(&maxUploadBytes, "max-upload-bytes", envInt64("POCKETHOST_FILED_MAX_UPLOAD_BYTES", 256<<20), "maximum multipart upload size in bytes")
	flag.Parse()

	log := pocket.NewLogger("filed")
	started := time.Now()
	token := os.Getenv("POCKETHOST_TOKEN")

	if err := pocket.EnsureDir(dataDir); err != nil {
		log.Fatalf("create data dir: %v", err)
	}

	handler := newHandler(dataDir, addr, started, token, maxUploadBytes, log)
	if err := pocket.ListenAndServeGracefully(addr, handler, log); err != nil && err != http.ErrServerClosed {
		log.Fatalf("server stopped: %v", err)
	}
}

func newHandler(dataDir, addr string, started time.Time, token string, maxUploadBytes int64, log interface{ Printf(string, ...any) }) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", pocket.HealthHandler("filed", addr, started, func() map[string]string {
		return map[string]string{"data_dir": dataDir, "token_required": boolString(token != ""), "max_upload_bytes": fmt.Sprintf("%d", maxUploadBytes)}
	}))
	mux.HandleFunc("/api/files", listHandler(dataDir))
	mux.HandleFunc("/api/upload", uploadHandler(dataDir, maxUploadBytes))
	mux.HandleFunc("/api/delete", deleteHandler(dataDir))
	mux.HandleFunc("/files/", downloadHandler(dataDir))
	return pocket.RequireToken(pocket.RequestLog(log, mux), token)
}

func listHandler(root string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			pocket.WriteJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
			return
		}
		reqPath := r.URL.Query().Get("path")
		full, err := pocket.SafeExistingPath(root, reqPath)
		if err != nil {
			pocket.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		entries, err := os.ReadDir(full)
		if err != nil {
			pocket.WriteJSON(w, http.StatusNotFound, map[string]string{"error": err.Error()})
			return
		}
		items := make([]fileItem, 0, len(entries))
		for _, entry := range entries {
			info, err := entry.Info()
			if err != nil {
				continue
			}
			rel := filepath.ToSlash(filepath.Join(reqPath, entry.Name()))
			items = append(items, fileItem{
				Name:    entry.Name(),
				Path:    strings.TrimPrefix(rel, "/"),
				IsDir:   entry.IsDir(),
				Size:    info.Size(),
				ModTime: info.ModTime().UTC().Format(time.RFC3339),
			})
		}
		sort.Slice(items, func(i, j int) bool {
			if items[i].IsDir != items[j].IsDir {
				return items[i].IsDir
			}
			return strings.ToLower(items[i].Name) < strings.ToLower(items[j].Name)
		})
		pocket.WriteJSON(w, http.StatusOK, map[string]any{"path": reqPath, "items": items})
	}
}

func uploadHandler(root string, maxUploadBytes int64) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			pocket.WriteJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
			return
		}
		if maxUploadBytes <= 0 {
			maxUploadBytes = 256 << 20
		}
		r.Body = http.MaxBytesReader(w, r.Body, maxUploadBytes)
		if err := r.ParseMultipartForm(maxUploadBytes); err != nil {
			pocket.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		targetPath := r.URL.Query().Get("path")
		targetDirRaw, err := pocket.SafeJoin(root, targetPath)
		if err != nil {
			pocket.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		if err := os.MkdirAll(targetDirRaw, 0o750); err != nil {
			pocket.WriteJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		targetDir, err := pocket.SafeExistingPath(root, targetPath)
		if err != nil {
			pocket.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		file, header, err := r.FormFile("file")
		if err != nil {
			pocket.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": "multipart field 'file' is required"})
			return
		}
		defer file.Close()
		name, err := sanitizeUploadName(header.Filename)
		if err != nil {
			pocket.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		dstPath := filepath.Join(targetDir, name)
		if info, err := os.Lstat(dstPath); err == nil && info.Mode()&os.ModeSymlink != 0 {
			pocket.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": "refusing to overwrite symlink"})
			return
		}
		tmp, err := os.CreateTemp(targetDir, ".upload-*.tmp")
		if err != nil {
			pocket.WriteJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		tmpPath := tmp.Name()
		committed := false
		defer func() {
			_ = tmp.Close()
			if !committed {
				_ = os.Remove(tmpPath)
			}
		}()
		n, err := io.Copy(tmp, file)
		if err != nil {
			pocket.WriteJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		if err := tmp.Chmod(0o640); err != nil {
			pocket.WriteJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		if err := tmp.Close(); err != nil {
			pocket.WriteJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		if err := os.Rename(tmpPath, dstPath); err != nil {
			pocket.WriteJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		committed = true
		pocket.WriteJSON(w, http.StatusCreated, map[string]any{"name": name, "bytes": n})
	}
}

func deleteHandler(root string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodDelete && r.Method != http.MethodPost {
			pocket.WriteJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
			return
		}
		var body struct {
			Path string `json:"path"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body.Path == "" {
			body.Path = r.URL.Query().Get("path")
		}
		full, err := pocket.SafeExistingPath(root, body.Path)
		if err != nil {
			pocket.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		if filepath.Clean(full) == filepath.Clean(root) {
			pocket.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": "refusing to delete root"})
			return
		}
		if err := os.RemoveAll(full); err != nil {
			pocket.WriteJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
			return
		}
		pocket.WriteJSON(w, http.StatusOK, map[string]string{"status": "deleted"})
	}
}

func downloadHandler(root string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			pocket.WriteJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
			return
		}
		rel := strings.TrimPrefix(r.URL.Path, "/files/")
		full, err := pocket.SafeExistingPath(root, rel)
		if err != nil {
			pocket.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		info, err := os.Stat(full)
		if err != nil {
			pocket.WriteJSON(w, http.StatusNotFound, map[string]string{"error": err.Error()})
			return
		}
		if info.IsDir() {
			pocket.WriteJSON(w, http.StatusForbidden, map[string]string{"error": "directory listing disabled; use /api/files"})
			return
		}
		http.ServeFile(w, r, full)
	}
}

func sanitizeUploadName(name string) (string, error) {
	if name == "" || name == "." || name == ".." {
		return "", fmt.Errorf("invalid filename")
	}
	if strings.ContainsAny(name, `/\`) || filepath.Base(name) != name {
		return "", fmt.Errorf("filename must not contain path separators")
	}
	return name, nil
}

func envInt64(key string, fallback int64) int64 {
	if v := os.Getenv(key); v != "" {
		var parsed int64
		if _, err := fmt.Sscanf(v, "%d", &parsed); err == nil && parsed > 0 {
			return parsed
		}
	}
	return fallback
}

func boolString(v bool) string {
	if v {
		return "true"
	}
	return "false"
}
