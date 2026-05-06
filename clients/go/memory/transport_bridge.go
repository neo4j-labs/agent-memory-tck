package memory

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// bridgeTransport speaks the TCK bridge protocol.
type bridgeTransport struct {
	endpoint   string
	apiKey     string
	headers    map[string]string
	httpClient *http.Client
}

func newBridgeTransport(cfg Config, timeout time.Duration) Transport {
	return &bridgeTransport{
		endpoint:   strings.TrimRight(cfg.Endpoint, "/"),
		apiKey:     cfg.APIKey,
		headers:    cfg.Headers,
		httpClient: &http.Client{Timeout: timeout},
	}
}

func (t *bridgeTransport) Connect(ctx context.Context) error {
	var result map[string]interface{}
	return t.Call(ctx, "setup", nil, &result)
}

func (t *bridgeTransport) Close(_ context.Context) error { return nil }

func (t *bridgeTransport) Call(ctx context.Context, method string, params map[string]interface{}, result interface{}) error {
	body, err := json.Marshal(params)
	if err != nil {
		return &MemoryError{Message: "failed to marshal request", Cause: err}
	}

	url := fmt.Sprintf("%s/%s", t.endpoint, method)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return &ConnectionError{MemoryError{Message: "failed to create request", Cause: err}}
	}

	req.Header.Set("Content-Type", "application/json")
	for k, v := range t.headers {
		req.Header.Set(k, v)
	}
	if t.apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+t.apiKey)
	}

	resp, err := t.httpClient.Do(req)
	if err != nil {
		return &ConnectionError{MemoryError{Message: fmt.Sprintf("request to %s failed", url), Cause: err}}
	}
	defer resp.Body.Close()

	if resp.StatusCode == 401 || resp.StatusCode == 403 {
		return &AuthenticationError{MemoryError{Message: fmt.Sprintf("authentication failed: %d", resp.StatusCode)}}
	}

	if resp.StatusCode == 204 {
		return nil
	}

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return &MemoryError{Message: "failed to read response", Cause: err}
	}

	if resp.StatusCode >= 400 {
		var errResp struct {
			Error string `json:"error"`
		}
		if json.Unmarshal(respBody, &errResp) == nil && errResp.Error != "" {
			return &TransportError{
				MemoryError: MemoryError{Message: fmt.Sprintf("%s failed: %s", method, errResp.Error)},
				StatusCode:  resp.StatusCode,
			}
		}
		return &TransportError{
			MemoryError: MemoryError{Message: fmt.Sprintf("%s failed: HTTP %d", method, resp.StatusCode)},
			StatusCode:  resp.StatusCode,
		}
	}

	if result != nil && len(respBody) > 0 {
		if err := json.Unmarshal(respBody, result); err != nil {
			return &MemoryError{Message: "failed to decode response", Cause: err}
		}
	}

	return nil
}
