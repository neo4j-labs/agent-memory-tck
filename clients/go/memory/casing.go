package memory

import (
	"strings"
	"unicode"
)

// snakeKey converts camelCase / PascalCase to snake_case.
func snakeKey(s string) string {
	var b strings.Builder
	for i, r := range s {
		if i > 0 && unicode.IsUpper(r) {
			b.WriteByte('_')
		}
		b.WriteRune(unicode.ToLower(r))
	}
	return b.String()
}

// camelKey converts snake_case to camelCase.
func camelKey(s string) string {
	var b strings.Builder
	upper := false
	for _, r := range s {
		if r == '_' {
			upper = true
			continue
		}
		if upper {
			b.WriteRune(unicode.ToUpper(r))
			upper = false
		} else {
			b.WriteRune(r)
		}
	}
	return b.String()
}

// snakeToCamel walks any value, rewriting map keys from snake_case to
// camelCase. Slice and primitive values are returned as-is.
func snakeToCamel(value interface{}) interface{} {
	switch v := value.(type) {
	case map[string]interface{}:
		out := make(map[string]interface{}, len(v))
		for k, val := range v {
			out[camelKey(k)] = snakeToCamel(val)
		}
		return out
	case []interface{}:
		out := make([]interface{}, len(v))
		for i, val := range v {
			out[i] = snakeToCamel(val)
		}
		return out
	default:
		return v
	}
}

// camelToSnake walks any value, rewriting map keys from camelCase to
// snake_case.
func camelToSnake(value interface{}) interface{} {
	switch v := value.(type) {
	case map[string]interface{}:
		out := make(map[string]interface{}, len(v))
		for k, val := range v {
			out[snakeKey(k)] = camelToSnake(val)
		}
		return out
	case []interface{}:
		out := make([]interface{}, len(v))
		for i, val := range v {
			out[i] = camelToSnake(val)
		}
		return out
	default:
		return v
	}
}
