package memory

import "context"

// QueryConsole exposes the read-only Cypher query endpoint of the hosted
// service.
type QueryConsole struct {
	transport Transport
}

// CypherInput is the input to QueryConsole.Cypher.
type CypherInput struct {
	Cypher string                 `json:"cypher"`
	Params map[string]interface{} `json:"params,omitempty"`
}

// Cypher executes a read-only Cypher query against the hosted service.
func (q *QueryConsole) Cypher(ctx context.Context, in CypherInput) (*CypherResult, error) {
	params := map[string]interface{}{
		"cypher": in.Cypher,
		"params": in.Params,
	}
	if in.Params == nil {
		params["params"] = map[string]interface{}{}
	}
	var result CypherResult
	if err := q.transport.Call(ctx, "cypher_query", params, &result); err != nil {
		return nil, err
	}
	return &result, nil
}
