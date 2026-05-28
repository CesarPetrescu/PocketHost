package main

import (
	"flag"
	"fmt"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"strings"
	"time"

	"dev.pockethost/daemons/internal/pocket"
)

type route struct {
	Target string
	Proxy  *httputil.ReverseProxy
}

func main() {
	var addr string
	var routesText string
	flag.StringVar(&addr, "addr", pocket.Env("POCKETHOST_PROXYD_ADDR", "127.0.0.1:8088"), "listen address")
	flag.StringVar(&routesText, "routes", os.Getenv("POCKETHOST_PROXY_ROUTES"), "comma-separated host=url routes")
	flag.Parse()

	log := pocket.NewLogger("proxyd")
	started := time.Now()
	routes := parseRoutes(routesText, log)
	if len(routes) == 0 {
		routes["web.local"] = "http://127.0.0.1:8080"
		routes["files.local"] = "http://127.0.0.1:8090"
		log.Printf("no routes configured; using local defaults")
	}

	handler := newProxyHandler(addr, started, routes, log)
	if err := pocket.ListenAndServeGracefully(addr, handler, log); err != nil && err != http.ErrServerClosed {
		log.Fatalf("server stopped: %v", err)
	}
}

func newProxyHandler(addr string, started time.Time, routes map[string]string, log interface{ Printf(string, ...any) }) http.Handler {
	compiled := map[string]route{}
	for host, target := range routes {
		parsed, err := url.Parse(target)
		if err != nil {
			log.Printf("route_compile_error host=%s target=%s error=%v", host, target, err)
			continue
		}
		compiled[host] = route{Target: target, Proxy: newReverseProxy(parsed, log)}
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/health", pocket.HealthHandler("proxyd", addr, started, func() map[string]string {
		extra := map[string]string{"route_count": stringInt(len(compiled))}
		for host, route := range compiled {
			extra["route."+host] = route.Target
		}
		return extra
	}))
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		host := normalizeHost(strings.Split(r.Host, ":")[0])
		route, ok := compiled[host]
		if !ok {
			pocket.WriteJSON(w, http.StatusNotFound, map[string]any{"error": "no route for host", "host": host})
			return
		}
		log.Printf("host=%s target=%s path=%s", host, route.Target, r.URL.Path)
		route.Proxy.ServeHTTP(w, r)
	})
	return pocket.RequestLog(log, mux)
}

func newReverseProxy(targetURL *url.URL, log interface{ Printf(string, ...any) }) *httputil.ReverseProxy {
	proxy := httputil.NewSingleHostReverseProxy(targetURL)
	originalDirector := proxy.Director
	proxy.Director = func(req *http.Request) {
		forwardedHost := req.Host
		originalDirector(req)
		req.Host = targetURL.Host
		req.Header.Set("X-PocketHost-Forwarded-Host", forwardedHost)
	}
	proxy.Transport = &http.Transport{
		Proxy: http.ProxyFromEnvironment,
		DialContext: (&net.Dialer{
			Timeout:   5 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          100,
		IdleConnTimeout:       60 * time.Second,
		TLSHandshakeTimeout:   5 * time.Second,
		ResponseHeaderTimeout: 30 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
	}
	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		log.Printf("proxy_error host=%s target=%s error=%v", r.Host, targetURL.String(), err)
		pocket.WriteJSON(w, http.StatusBadGateway, map[string]string{"error": "upstream unavailable"})
	}
	proxy.ModifyResponse = func(resp *http.Response) error {
		resp.Header.Set("X-PocketHost-Upstream", targetURL.Host)
		return nil
	}
	proxy.FlushInterval = 100 * time.Millisecond
	return proxy
}

func parseRoutes(text string, loggers ...interface{ Printf(string, ...any) }) map[string]string {
	routes := map[string]string{}
	for _, pair := range strings.Split(text, ",") {
		pair = strings.TrimSpace(pair)
		if pair == "" {
			continue
		}
		parts := strings.SplitN(pair, "=", 2)
		if len(parts) != 2 {
			logRouteDrop(loggers, pair, "missing =")
			continue
		}
		host := normalizeHost(parts[0])
		target := strings.TrimSpace(parts[1])
		if err := validateRouteHost(host); err != nil {
			logRouteDrop(loggers, pair, err.Error())
			continue
		}
		if err := validateTargetURL(target); err != nil {
			logRouteDrop(loggers, pair, err.Error())
			continue
		}
		routes[host] = target
	}
	return routes
}

func logRouteDrop(loggers []interface{ Printf(string, ...any) }, pair, reason string) {
	for _, logger := range loggers {
		if logger != nil {
			logger.Printf("route_dropped pair=%q reason=%q", pair, reason)
		}
	}
}

func normalizeHost(host string) string {
	return strings.TrimSuffix(strings.ToLower(strings.TrimSpace(host)), ".")
}

func validateRouteHost(host string) error {
	if host == "" {
		return fmt.Errorf("empty host")
	}
	if strings.ContainsAny(host, "/\\:@ 	\n\r") {
		return fmt.Errorf("host contains invalid characters")
	}
	if strings.HasPrefix(host, ".") || strings.HasSuffix(host, ".") {
		return fmt.Errorf("host has invalid dot placement")
	}
	return nil
}

func validateTargetURL(target string) error {
	parsed, err := url.Parse(target)
	if err != nil {
		return err
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return fmt.Errorf("target scheme must be http or https")
	}
	if parsed.Host == "" {
		return fmt.Errorf("target host required")
	}
	if strings.ContainsAny(parsed.Host, " 	\n\r") {
		return fmt.Errorf("target host contains whitespace")
	}
	host := parsed.Hostname()
	if host == "" {
		return fmt.Errorf("target hostname required")
	}
	if ip := net.ParseIP(host); ip != nil && ip.IsUnspecified() {
		return fmt.Errorf("target must not be unspecified address")
	}
	return nil
}

func stringInt(v int) string {
	if v == 0 {
		return "0"
	}
	digits := ""
	for v > 0 {
		digits = string(rune('0'+(v%10))) + digits
		v /= 10
	}
	return digits
}
