---
---

Go: Decode hosted Cypher results as objects keyed by their original column aliases, preserving nested map keys. `CypherResult.Rows` changes from `[][]interface{}` to `[]map[string]interface{}`; use `result.Rows[0]["total"]` instead of positional column indexes.
