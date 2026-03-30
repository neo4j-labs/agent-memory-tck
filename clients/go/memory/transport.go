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

// transport handles HTTP communication with the backend service.
type transport struct {
	endpoint   string
	apiKey     string
	httpClient *http.Client
}

func newTransport(cfg Config) *transport {
	timeout := cfg.Timeout
	if timeout == 0 {
		timeout = 30 * time.Second
	}
	return &transport{
		endpoint: strings.TrimRight(cfg.Endpoint, "/"),
		apiKey:   cfg.APIKey,
		httpClient: &http.Client{
			Timeout: timeout,
		},
	}
}

// call makes a POST request to the given method endpoint and decodes the response.
func (t *transport) call(ctx context.Context, method string, params map[string]interface{}, result interface{}) error {
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
