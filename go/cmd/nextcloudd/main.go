package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"dev.pockethost/daemons/internal/pocket"
)

func main() {
	var addr string
	var phpAddr string
	var phpPath string
	var phpRuntimeDir string
	var phpIni string
	var phpExtensionDir string
	var phpMemoryLimit string
	var nextcloudDir string
	var dataDir string
	flag.StringVar(&addr, "addr", pocket.Env("POCKETHOST_NEXTCLOUD_ADDR", "127.0.0.1:8092"), "public wrapper listen address")
	flag.StringVar(&phpAddr, "php-addr", pocket.Env("POCKETHOST_NEXTCLOUD_PHP_ADDR", "127.0.0.1:8093"), "loopback PHP listen address")
	flag.StringVar(&phpPath, "php", pocket.Env("POCKETHOST_PHP", "./libphp.so"), "PHP executable path")
	flag.StringVar(&phpRuntimeDir, "php-runtime-dir", pocket.Env("POCKETHOST_PHP_RUNTIME_DIR", "./runtime/php"), "PHP runtime root containing lib, extensions, and config")
	flag.StringVar(&phpIni, "php-ini", pocket.Env("POCKETHOST_PHP_INI", ""), "PHP ini file path")
	flag.StringVar(&phpExtensionDir, "php-extension-dir", pocket.Env("POCKETHOST_PHP_EXTENSION_DIR", ""), "PHP extension directory")
	flag.StringVar(&phpMemoryLimit, "php-memory-limit", pocket.Env("POCKETHOST_PHP_MEMORY_LIMIT", "512M"), "PHP memory_limit override")
	flag.StringVar(&nextcloudDir, "nextcloud-dir", pocket.Env("POCKETHOST_NEXTCLOUD_DIR", "./data/nextcloud/server"), "Nextcloud server directory")
	flag.StringVar(&dataDir, "data-dir", pocket.Env("POCKETHOST_NEXTCLOUD_DATA", "./data/nextcloud/data"), "Nextcloud data directory")
	flag.Parse()

	log := pocket.NewLogger("nextcloudd")
	started := time.Now()

	phpEnv := buildPHPEnv(phpRuntimeDir, phpIni, phpExtensionDir, dataDir)
	if err := preflight(addr, phpPath, phpRuntimeDir, phpIni, phpExtensionDir, nextcloudDir, dataDir, phpEnv); err != nil {
		log.Fatalf("preflight failed: %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	cmd := exec.CommandContext(ctx, phpPath,
		"-c", phpIni,
		"-d", "variables_order=EGPCS",
		"-d", "memory_limit="+phpMemoryLimit,
		"-d", "extension_dir="+phpExtensionDir,
		"-S", phpAddr,
		"-t", nextcloudDir,
	)
	cmd.Env = append(os.Environ(),
		"NEXTCLOUD_DATA_DIR="+dataDir,
		"POCKETHOST_NEXTCLOUD_EXPERIMENTAL=true",
	)
	cmd.Env = append(cmd.Env, phpEnv...)
	cmd.Stdout = logWriter{log: log}
	cmd.Stderr = logWriter{log: log}
	if err := cmd.Start(); err != nil {
		log.Fatalf("failed to start PHP runtime: %v", err)
	}
	defer func() {
		cancel()
		_ = cmd.Process.Kill()
		_, _ = cmd.Process.Wait()
	}()

	target, _ := url.Parse("http://" + phpAddr)
	proxy := httputil.NewSingleHostReverseProxy(target)
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			pocket.WriteError(w, http.StatusMethodNotAllowed, "method not allowed")
			return
		}
		pocket.WriteJSON(w, http.StatusOK, map[string]any{
			"service":         "nextcloudd",
			"status":          "ok",
			"addr":            addr,
			"php_addr":        phpAddr,
			"php_runtime_dir": phpRuntimeDir,
			"php_ini":         phpIni,
			"php_extensions":  phpExtensionDir,
			"uptime_seconds":  int64(time.Since(started).Seconds()),
			"nextcloud_dir":   nextcloudDir,
			"data_dir":        dataDir,
		})
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		proxy.ServeHTTP(w, r)
	})

	go func() {
		if err := cmd.Wait(); err != nil {
			log.Printf("PHP runtime exited: %v", err)
		}
		cancel()
	}()

	if err := pocket.ListenAndServeGracefully(addr, mux, log); err != nil && err != http.ErrServerClosed {
		log.Fatalf("server stopped: %v", err)
	}
}

func preflight(addr, phpPath, phpRuntimeDir, phpIni, phpExtensionDir, nextcloudDir, dataDir string, phpEnv []string) error {
	allowPublic := os.Getenv("POCKETHOST_ALLOW_PUBLIC_BIND") == "true"
	if err := pocket.ValidateListenAddr(addr, allowPublic); err != nil {
		return err
	}
	if info, err := os.Stat(phpPath); err != nil || info.IsDir() {
		return fmt.Errorf("missing PHP runtime: %s", phpPath)
	}
	if info, err := os.Stat(phpRuntimeDir); err != nil || !info.IsDir() {
		return fmt.Errorf("missing PHP runtime directory: %s", phpRuntimeDir)
	}
	if info, err := os.Stat(phpIni); err != nil || info.IsDir() {
		return fmt.Errorf("missing PHP ini: %s", phpIni)
	}
	if info, err := os.Stat(phpExtensionDir); err != nil || !info.IsDir() {
		return fmt.Errorf("missing PHP extension directory: %s", phpExtensionDir)
	}
	if info, err := os.Stat(nextcloudDir); err != nil || !info.IsDir() {
		return fmt.Errorf("missing Nextcloud server directory: %s", nextcloudDir)
	}
	if err := os.MkdirAll(dataDir, 0700); err != nil {
		return fmt.Errorf("create data dir: %w", err)
	}
	index := filepath.Join(nextcloudDir, "index.php")
	if info, err := os.Stat(index); err != nil || info.IsDir() {
		return fmt.Errorf("missing Nextcloud index.php: %s", index)
	}
	if err := verifyPHPExtensions(phpPath, phpIni, phpExtensionDir, phpEnv); err != nil {
		return err
	}
	return nil
}

func buildPHPEnv(phpRuntimeDir, phpIni, phpExtensionDir, dataDir string) []string {
	libDir := filepath.Join(phpRuntimeDir, "lib")
	tmpDir := filepath.Join(dataDir, "..", "tmp")
	ldPath := libDir
	if existing := os.Getenv("LD_LIBRARY_PATH"); existing != "" {
		ldPath += string(os.PathListSeparator) + existing
	}
	return []string{
		"LD_LIBRARY_PATH=" + ldPath,
		"PHPRC=" + filepath.Dir(phpIni),
		"PHP_INI_SCAN_DIR=",
		"TMPDIR=" + filepath.Clean(tmpDir),
		"POCKETHOST_PHP_EXTENSION_DIR=" + phpExtensionDir,
	}
}

func verifyPHPExtensions(phpPath, phpIni, phpExtensionDir string, phpEnv []string) error {
	required := []string{
		"sqlite3", "pdo_sqlite", "mbstring", "intl", "xml", "xmlreader", "xmlwriter",
		"simplexml", "dom", "zip", "curl", "gd", "fileinfo", "openssl", "sodium",
		"ctype", "session", "zlib", "posix",
	}
	cmd := exec.Command(phpPath, "-c", phpIni, "-d", "extension_dir="+phpExtensionDir, "-m")
	cmd.Env = append(os.Environ(), phpEnv...)
	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("php -m failed: %v: %s", err, strings.TrimSpace(string(output)))
	}
	loaded := map[string]bool{}
	for _, line := range strings.Split(string(output), "\n") {
		name := strings.ToLower(strings.TrimSpace(line))
		if name != "" && !strings.HasPrefix(name, "[") {
			loaded[name] = true
		}
	}
	var missing []string
	for _, ext := range required {
		if !loaded[strings.ToLower(ext)] {
			missing = append(missing, ext)
		}
	}
	if len(missing) > 0 {
		return fmt.Errorf("missing PHP extensions: %s", strings.Join(missing, ", "))
	}
	return nil
}

type logWriter struct {
	log logSink
}

func (w logWriter) Write(p []byte) (int, error) {
	msg := string(p)
	if len(msg) > 0 {
		w.log.Printf("php: %q", msg)
	}
	return len(p), nil
}

type logSink interface {
	Printf(format string, v ...any)
}

var _ io.Writer = logWriter{}
