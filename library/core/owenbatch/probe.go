package owenbatch

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	core "github.com/exclavenetwork/exclave-core/v5"
	vnet "github.com/exclavenetwork/exclave-core/v5/common/net"
	"github.com/exclavenetwork/exclave-core/v5/infra/conf/serial"
	_ "github.com/exclavenetwork/exclave-core/v5/main/distro/all"
)

// session owns cancellation and limits concurrent native instances to 128.
// callers should use a bounded producer queue and 64 workers by default.
type Session struct {
	ctx    context.Context
	cancel context.CancelFunc
	slots  chan struct{}
}

func NewSession() *Session {
	ctx, cancel := context.WithCancel(context.Background())
	return &Session{ctx: ctx, cancel: cancel, slots: make(chan struct{}, 128)}
}

func (s *Session) Cancel() { s.cancel() }

// probe returns header latency in milliseconds. Cancellation is an error, never
// a dead-profile result. the original outbound chain and UDP transports remain intact.
func (s *Session) Probe(configJSON, link string, timeoutMillis int32) (int32, error) {
	ctx, cancel := context.WithTimeout(s.ctx, time.Duration(timeoutMillis)*time.Millisecond)
	defer cancel()
	select {
	case s.slots <- struct{}{}:
		defer func() { <-s.slots }()
	case <-ctx.Done():
		return 0, ctx.Err()
	}
	if err := ctx.Err(); err != nil {
		return 0, err
	}
	content, err := minimalConfig(configJSON)
	if err != nil {
		return 0, err
	}
	config, err := serial.LoadJSONConfig(strings.NewReader(content))
	if err != nil {
		return 0, err
	}
	instance, err := core.New(config)
	if err != nil {
		return 0, err
	}
	var closeOnce sync.Once
	closeCore := func() { closeOnce.Do(func() { instance.Close() }) }
	defer closeCore()
	if err = instance.Start(); err != nil {
		return 0, err
	}
	// core transports may retain background work beyond the HTTP request.
	stop := context.AfterFunc(ctx, closeCore)
	defer stop()
	return probeHTTP(ctx, link, &http.Transport{DisableKeepAlives: true, ForceAttemptHTTP2: true, DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
		dest, err := vnet.ParseDestination(network + ":" + addr)
		if err != nil {
			return nil, err
		}
		return core.Dial(ctx, instance, dest)
	}})
}

func minimalConfig(content string) (string, error) {
	var config map[string]json.RawMessage
	if err := json.Unmarshal([]byte(content), &config); err != nil {
		return "", err
	}
	// no listeners, statistics, periodic probes or browser helper servers.
	for _, key := range []string{"inbounds", "stats", "api", "observatory", "multiObservatory", "browserForwarder", "browserDialer"} {
		delete(config, key)
	}
	result, err := json.Marshal(config)
	return string(result), err
}

// probeProxy tests a running fallback instance through its loopback HTTP inbound.
func (s *Session) ProbeProxy(address, link string, timeoutMillis int32) (int32, error) {
	ctx, cancel := context.WithTimeout(s.ctx, time.Duration(timeoutMillis)*time.Millisecond)
	defer cancel()
	select {
	case s.slots <- struct{}{}:
		defer func() { <-s.slots }()
	case <-ctx.Done():
		return 0, ctx.Err()
	}
	proxy, err := url.Parse("http://" + address)
	if err != nil {
		return 0, err
	}
	return probeHTTP(ctx, link, &http.Transport{Proxy: http.ProxyURL(proxy), DisableKeepAlives: true, ForceAttemptHTTP2: true})
}

func probeHTTP(ctx context.Context, link string, transport *http.Transport) (int32, error) {
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	start := time.Now()
	for _, method := range []string{http.MethodHead, http.MethodGet} {
		req, err := http.NewRequestWithContext(ctx, method, link, nil)
		if err != nil {
			return 0, err
		}
		if req.URL.Scheme != "http" && req.URL.Scheme != "https" {
			return 0, fmt.Errorf("unsupported probe URL scheme")
		}
		resp, err := client.Do(req)
		if err != nil {
			return 0, err
		}
		resp.Body.Close()
		if resp.StatusCode >= 200 && resp.StatusCode < 300 {
			return int32(max(1, time.Since(start).Milliseconds())), nil
		}
		if method == http.MethodHead && (resp.StatusCode == 405 || resp.StatusCode == 501) {
			continue
		}
		return 0, fmt.Errorf("probe HTTP status %d", resp.StatusCode)
	}
	return 0, fmt.Errorf("probe failed")
}
