package owenbatch

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestHTTPStatus(t *testing.T) {
	for _, status := range []int{200, 204, 301, 403, 503} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(status) }))
			defer server.Close()
			_, err := probeHTTP(context.Background(), server.URL, &http.Transport{})
			if (err == nil) != (status >= 200 && status < 300) {
				t.Fatalf("status %d: %v", status, err)
			}
		})
	}
}

func TestHEADFallback(t *testing.T) {
	var gets atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodHead {
			w.WriteHeader(405)
			return
		}
		gets.Add(1)
		w.WriteHeader(204)
	}))
	defer server.Close()
	if _, err := probeHTTP(context.Background(), server.URL, &http.Transport{}); err != nil {
		t.Fatal(err)
	}
	if gets.Load() != 1 {
		t.Fatal("missing GET fallback")
	}
}

func TestCancelHTTP(t *testing.T) {
	started := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(started)
		<-r.Context().Done()
	}))
	defer server.Close()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { _, err := probeHTTP(ctx, server.URL, &http.Transport{}); done <- err }()
	<-started
	cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancelled probe succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("HTTP cancellation stalled")
	}
}

func TestMinimalConfig(t *testing.T) {
	content, err := minimalConfig(`{"inbounds":[{}],"stats":{},"observatory":{},"outbounds":[{"protocol":"hysteria2"}],"dns":{"servers":["localhost"]}}`)
	if err != nil {
		t.Fatal(err)
	}
	var config map[string]json.RawMessage
	json.Unmarshal([]byte(content), &config)
	if config["inbounds"] != nil || config["observatory"] != nil || config["stats"] != nil {
		t.Fatal(content)
	}
	if config["outbounds"] == nil || config["dns"] == nil {
		t.Fatal("outbound or DNS lost")
	}
}

func TestCancelledSession(t *testing.T) {
	session := NewSession()
	session.Cancel()
	if _, err := session.Probe(`{}`, "http://localhost", 1000); err == nil {
		t.Fatal("cancelled session succeeded")
	}
}

func TestCoreProbe(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer server.Close()
	session := NewSession()
	defer session.Cancel()
	_, err := session.Probe(`{"outbounds":[{"protocol":"freedom","settings":{}}]}`, server.URL, 1000)
	if err != nil {
		t.Fatal(err)
	}
}

func TestCancelQueuedProbe(t *testing.T) {
	session := NewSession()
	for range cap(session.slots) {
		session.slots <- struct{}{}
	}
	done := make(chan error, 1)
	go func() { _, err := session.Probe(`{}`, "http://localhost", 60000); done <- err }()
	session.Cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancelled queued probe succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("queued probe did not cancel")
	}
}

func TestCancelRunningCore(t *testing.T) {
	started := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(started)
		<-r.Context().Done()
	}))
	defer server.Close()
	session := NewSession()
	defer session.Cancel()
	done := make(chan error, 1)
	go func() {
		_, err := session.Probe(`{"outbounds":[{"protocol":"freedom","settings":{}}]}`, server.URL, 60000)
		done <- err
	}()
	select {
	case <-started:
	case <-time.After(2 * time.Second):
		t.Fatal("core probe did not start")
	}
	session.Cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancelled core succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("core did not cancel")
	}
}
