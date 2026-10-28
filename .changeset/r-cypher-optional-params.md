---
---

R: Omit absent optional parameters and explicit empty parameter lists (`params = list()`) from Cypher requests so unparameterized queries no longer send an invalid `params: []` payload. Named parameter lists continue to serialize as JSON objects.
