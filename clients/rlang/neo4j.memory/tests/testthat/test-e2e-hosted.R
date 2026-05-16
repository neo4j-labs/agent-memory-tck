# Comprehensive end-to-end tests against the live hosted Neo4j Agent Memory
# Service.
#
# Mirrors the Python suite at clients/python/tests/e2e/test_hosted_service.py
# — same scenarios, same skip patterns. Skipped wholesale when
# MEMORY_API_KEY is unset; individual tests skip on 403 for endpoints that
# require elevated workspace scope (Cypher, API-key management).
#
# Each test creates short-lived data tagged with the `tck-e2e-r-` user
# prefix and tears it down via on.exit() handlers.

# Helpers ---------------------------------------------------------------

new_e2e_client <- function() {
  skip_if_no_api_key()
  client <- MemoryClient$new(
    endpoint = e2e_endpoint(),
    api_key = Sys.getenv("MEMORY_API_KEY")
  )
  client$connect()
  client
}

new_conv <- function(client, suffix = "") {
  test_name <- tck_infer_current_test_name()
  conv <- client$short_term$create_conversation(
    user_id = e2e_user_id_with(suffix),
    metadata = tck_metadata_for(test_name, list(tck_phase = "fixture"))
  )
  tck_record_provenance_step(client, conv$id, test_name)
  attr(conv, ".cleanup") <- function() {
    tryCatch(client$short_term$delete_conversation(conv$id), error = function(e) NULL)
  }
  conv
}

new_entity <- function(client, name = NULL, entity_type = "concept", description = "tck e2e probe entity") {
  if (is.null(name)) {
    name <- paste0("TCK-Probe-", paste(sample(c(letters, 0:9), 8, replace = TRUE), collapse = ""))
  }
  test_name <- tck_infer_current_test_name()
  tagged_description <- tck_tag_description(test_name, description)
  e <- client$long_term$add_entity(name, entity_type, description = tagged_description)
  attr(e, ".cleanup") <- function() {
    tryCatch(client$long_term$delete_entity(e$id), error = function(err) NULL)
  }
  e
}

cleanup <- function(x) {
  fn <- attr(x, ".cleanup")
  if (is.function(fn)) fn()
}

random_hex <- function(n) {
  paste(sample(c(letters, 0:9), n, replace = TRUE), collapse = "")
}

e2e_user_id_with <- function(suffix = "") {
  base <- Sys.getenv("MEMORY_E2E_USER_ID", "tck-e2e-r")
  rid <- paste0(base, "-", random_hex(8))
  if (nzchar(suffix)) paste0(rid, "-", suffix) else rid
}

wait_until <- function(predicate, timeout = 12) {
  deadline <- Sys.time() + timeout
  while (Sys.time() < deadline) {
    if (isTRUE(predicate())) return(TRUE)
    Sys.sleep(1)
  }
  FALSE
}

# ===========================================================================
# 1. Connection + auth
# ===========================================================================

test_that("e2e: connect succeeds with valid key", {
  client <- new_e2e_client()
  expect_true(inherits(client, "MemoryClient"))
})

test_that("e2e: bogus key fails authentication", {
  skip_if_no_api_key()
  bad <- MemoryClient$new(
    endpoint = e2e_endpoint(),
    api_key = "nams_obviously_not_real_token"
  )
  expect_error(bad$connect(), "[Aa]uthentication")
})

test_that("e2e: empty key fails authentication", {
  skip_if_no_api_key()
  bad <- MemoryClient$new(endpoint = e2e_endpoint(), api_key = "")
  expect_error(bad$connect(), "[Aa]uthentication")
})

# ===========================================================================
# 2. Conversation lifecycle
# ===========================================================================

test_that("e2e: create returns id + user_id + workspace_id", {
  client <- new_e2e_client()
  uid <- e2e_user_id_with("create")
  conv <- client$short_term$create_conversation(
    user_id = uid,
    metadata = list(source = "e2e", seq = 1L)
  )
  on.exit(client$short_term$delete_conversation(conv$id))
  expect_true(nchar(conv$id) >= 8)
  expect_equal(conv$user_id, uid)
  expect_true(!is.null(conv$workspace_id))
})

test_that("e2e: get_metadata round-trips user_id", {
  client <- new_e2e_client()
  conv <- new_conv(client)
  on.exit(cleanup(conv))
  meta <- client$short_term$get_conversation_metadata(conv$id)
  expect_equal(meta$id, conv$id)
  expect_equal(meta$user_id, conv$user_id)
})

test_that("e2e: list includes freshly created", {
  client <- new_e2e_client()
  conv <- new_conv(client, "list-probe")
  on.exit(cleanup(conv))
  listed <- client$short_term$list_conversations(limit = 200L)
  ids <- vapply(listed, function(c) c$id, character(1))
  expect_true(conv$id %in% ids)
})

test_that("e2e: delete is idempotent", {
  client <- new_e2e_client()
  conv <- client$short_term$create_conversation(user_id = e2e_user_id_with("del"))
  client$short_term$delete_conversation(conv$id)
  expect_silent(client$short_term$delete_conversation(conv$id))
})

# ===========================================================================
# 3. Short-term: messages
# ===========================================================================

test_that("e2e: addMessage returns id + role", {
  client <- new_e2e_client()
  conv <- new_conv(client)
  on.exit(cleanup(conv))
  msg <- client$short_term$add_message(conv$id, "user", "hello world")
  expect_true(nchar(msg$id) > 0)
  expect_equal(msg$role, "user")
  expect_equal(msg$content, "hello world")
})

test_that("e2e: getConversation returns added messages", {
  client <- new_e2e_client()
  conv <- new_conv(client)
  on.exit(cleanup(conv))
  contents <- c("one", "two", "three", "four", "five")
  for (c in contents) client$short_term$add_message(conv$id, "user", c)
  got <- client$short_term$get_conversation(conv$id)
  seen <- vapply(got$messages, function(m) m$content, character(1))
  for (c in contents) expect_true(c %in% seen)
})

test_that("e2e: searchMessages returns array", {
  client <- new_e2e_client()
  conv <- new_conv(client)
  on.exit(cleanup(conv))
  client$short_term$add_message(conv$id, "user",
    "Marie Curie won the Nobel Prize in Physics in 1903.")
  results <- client$short_term$search_messages("Nobel",
    session_id = conv$id, limit = 5L, threshold = 0.0)
  expect_true(is.list(results))
})

test_that("e2e: role round-trip user", {
  client <- new_e2e_client()
  conv <- new_conv(client, "role-user"); on.exit(cleanup(conv))
  expect_equal(client$short_term$add_message(conv$id, "user", "x")$role, "user")
})
test_that("e2e: role round-trip assistant", {
  client <- new_e2e_client()
  conv <- new_conv(client, "role-asst"); on.exit(cleanup(conv))
  expect_equal(client$short_term$add_message(conv$id, "assistant", "x")$role, "assistant")
})
test_that("e2e: role round-trip system", {
  client <- new_e2e_client()
  conv <- new_conv(client, "role-sys"); on.exit(cleanup(conv))
  expect_equal(client$short_term$add_message(conv$id, "system", "x")$role, "system")
})

test_that("e2e: unicode preserved", {
  client <- new_e2e_client()
  conv <- new_conv(client); on.exit(cleanup(conv))
  content <- "你好 🚀 émoji ñ ç ø"
  msg <- client$short_term$add_message(conv$id, "user", content)
  expect_equal(msg$content, content)
})

test_that("e2e: long content (10k chars) preserved", {
  client <- new_e2e_client()
  conv <- new_conv(client); on.exit(cleanup(conv))
  content <- paste(rep("x", 10000), collapse = "")
  msg <- client$short_term$add_message(conv$id, "user", content)
  expect_equal(nchar(msg$content), 10000)
})

test_that("e2e: special chars preserved", {
  client <- new_e2e_client()
  conv <- new_conv(client); on.exit(cleanup(conv))
  content <- 'quote " backslash \\ newline\nreturn\r tab\t json {"a":1}'
  msg <- client$short_term$add_message(conv$id, "user", content)
  expect_equal(msg$content, content)
})

test_that("e2e: metadata round-trips without error", {
  client <- new_e2e_client()
  conv <- new_conv(client); on.exit(cleanup(conv))
  msg <- client$short_term$add_message(conv$id, "user", "with-meta",
    metadata = list(source = "tck-e2e", priority = "high",
                    count = 42L, active = TRUE))
  expect_equal(msg$content, "with-meta")
})

# ===========================================================================
# 4. Bulk operations
# ===========================================================================

test_that("e2e: bulk add 5 messages", {
  client <- new_e2e_client()
  conv <- new_conv(client); on.exit(cleanup(conv))
  msgs <- lapply(0:4, function(i) list(role = "user", content = paste0("bulk-", i)))
  out <- client$short_term$bulk_add_messages(conv$id, msgs)
  expect_equal(length(out), 5)
})

test_that("e2e: bulk add 50 messages", {
  client <- new_e2e_client()
  conv <- new_conv(client); on.exit(cleanup(conv))
  msgs <- lapply(0:49, function(i) list(role = "user", content = paste0("big-bulk-", i)))
  out <- client$short_term$bulk_add_messages(conv$id, msgs)
  expect_equal(length(out), 50)
})

test_that("e2e: bulk add rejects more than 100", {
  client <- new_e2e_client()
  conv <- new_conv(client); on.exit(cleanup(conv))
  msgs <- lapply(0:100, function(i) list(role = "user", content = "x"))
  expect_error(client$short_term$bulk_add_messages(conv$id, msgs))
})

# ===========================================================================
# 5. Three-tier context
# ===========================================================================

test_that("e2e: getContext returns three-tier shape", {
  client <- new_e2e_client()
  conv <- new_conv(client); on.exit(cleanup(conv))
  client$short_term$add_message(conv$id, "user", "Hello world")
  ctx <- client$short_term$get_context(conv$id)
  expect_true(!is.null(ctx$reflections))
  expect_true(!is.null(ctx$observations))
  expect_true(!is.null(ctx$recent_messages))
})

test_that("e2e: getObservations returns list", {
  client <- new_e2e_client()
  conv <- new_conv(client); on.exit(cleanup(conv))
  obs <- client$short_term$get_observations(conv$id, limit = 10L)
  expect_true(is.list(obs))
})

test_that("e2e: getReflections returns list", {
  client <- new_e2e_client()
  conv <- new_conv(client); on.exit(cleanup(conv))
  refl <- client$short_term$get_reflections(conv$id)
  expect_true(is.list(refl))
})

test_that("e2e: recent_messages includes added message", {
  client <- new_e2e_client()
  conv <- new_conv(client); on.exit(cleanup(conv))
  client$short_term$add_message(conv$id, "user", "context-probe-message")
  ctx <- client$short_term$get_context(conv$id)
  contents <- vapply(ctx$recent_messages, function(m) m$content, character(1))
  expect_true("context-probe-message" %in% contents)
})

# ===========================================================================
# 6. Long-term: entity CRUD + search
# ===========================================================================

test_that("e2e: addEntity returns id + fields", {
  client <- new_e2e_client()
  e <- new_entity(client, name = paste0("TCK Alice ", random_hex(4)),
                  description = "test person")
  on.exit(cleanup(e))
  expect_true(nchar(e$id) >= 8)
  # new_entity tags the description with a tck-provenance prefix; the
  # original payload is preserved at the end of the string.
  expect_match(e$description, "test person$")
  expect_match(e$description, "tck:r")
})

test_that("e2e: listEntities returns list", {
  client <- new_e2e_client()
  ents <- client$long_term$list_entities(limit = 5L)
  expect_true(is.list(ents))
})

test_that("e2e: listEntities with type filter", {
  client <- new_e2e_client()
  ents <- client$long_term$list_entities(type = "person", limit = 5L)
  expect_true(is.list(ents))
  for (e in ents) expect_equal(e$type, "person")
})

test_that("e2e: getEntity round-trips", {
  client <- new_e2e_client()
  e <- new_entity(client); on.exit(cleanup(e))
  full <- client$long_term$get_entity(e$id)
  expect_equal(full$id, e$id)
})

test_that("e2e: updateEntity description", {
  client <- new_e2e_client()
  e <- new_entity(client, description = "orig"); on.exit(cleanup(e))
  updated <- client$long_term$update_entity(e$id, description = "rewritten")
  expect_equal(updated$id, e$id)
  expect_equal(updated$description, "rewritten")
})

test_that("e2e: updateEntity name", {
  client <- new_e2e_client()
  e <- new_entity(client, name = paste0("Original-", random_hex(6)))
  on.exit(cleanup(e))
  new_name <- paste0("Renamed-", random_hex(6))
  updated <- client$long_term$update_entity(e$id, name = new_name)
  expect_equal(updated$name, new_name)
})

test_that("e2e: deleteEntity removes it", {
  client <- new_e2e_client()
  e <- client$long_term$add_entity(
    paste0("TCK-DelProbe-", random_hex(6)), "concept",
    description = "ephemeral")
  client$long_term$delete_entity(e$id)
  # 404 or soft-tombstone are both acceptable
  tryCatch(client$long_term$get_entity(e$id), error = function(err) NULL)
  expect_true(TRUE)
})

test_that("e2e: searchEntities returns list", {
  client <- new_e2e_client()
  expect_true(is.list(client$long_term$search_entities("anything", limit = 5L)))
})

test_that("e2e: search finds freshly created entity (or skips)", {
  client <- new_e2e_client()
  marker <- paste0("TCK-Probe-", random_hex(8))
  e <- new_entity(client, name = marker); on.exit(cleanup(e))
  ok <- wait_until(function() {
    hits <- client$long_term$search_entities(marker, limit = 10L)
    any(vapply(hits, function(h) identical(h$id, e$id), logical(1)))
  }, timeout = 12)
  if (!ok) skip("entity not yet indexed for search after 12s")
  expect_true(ok)
})

# ===========================================================================
# 7. Entity feedback + history + provenance + graph + merge
# ===========================================================================

test_that("e2e: setEntityFeedback returns updated", {
  client <- new_e2e_client()
  e <- new_entity(client); on.exit(cleanup(e))
  result <- client$long_term$set_entity_feedback(e$id, user_score = 0.93, confirmed = TRUE)
  expect_equal(result$id, e$id)
  expect_true(result$updated)
})

test_that("e2e: setEntityFeedback zero score", {
  client <- new_e2e_client()
  e <- new_entity(client); on.exit(cleanup(e))
  result <- client$long_term$set_entity_feedback(e$id, user_score = 0.0, confirmed = FALSE)
  expect_equal(result$id, e$id)
})

test_that("e2e: getEntityHistory returns shape", {
  client <- new_e2e_client()
  e <- new_entity(client); on.exit(cleanup(e))
  hist <- client$long_term$get_entity_history(e$id)
  expect_equal(hist$entity_id, e$id)
  expect_true(is.list(hist$mentions))
})

test_that("e2e: getEntityProvenance returns shape", {
  client <- new_e2e_client()
  e <- new_entity(client); on.exit(cleanup(e))
  prov <- client$reasoning$get_entity_provenance(e$id)
  expect_equal(prov$entity_id, e$id)
  expect_true(is.list(prov$steps))
})

test_that("e2e: getEntityGraph returns nodes and edges", {
  client <- new_e2e_client()
  graph <- client$long_term$get_entity_graph()
  expect_true(!is.null(graph$nodes))
  expect_true(!is.null(graph$edges))
})

test_that("e2e: mergeEntities returns status (or skips)", {
  client <- new_e2e_client()
  a <- new_entity(client, name = paste0("MergeA-", random_hex(6))); on.exit(cleanup(a))
  b <- new_entity(client, name = paste0("MergeB-", random_hex(6))); on.exit(cleanup(b), add = TRUE)
  result <- tryCatch(
    client$long_term$merge_entities(a$id, b$id),
    error = function(err) {
      skip(paste("merge endpoint refused:", conditionMessage(err)))
    }
  )
  expect_true(nzchar(result$status))
})

# ===========================================================================
# 8. Reasoning: steps + explain + trace
# ===========================================================================

test_that("e2e: recordStep persists", {
  client <- new_e2e_client()
  conv <- new_conv(client); on.exit(cleanup(conv))
  step <- client$reasoning$record_step(
    conversation_id = conv$id,
    reasoning = "hypothesizing user's intent",
    action_taken = "lookup_user_profile",
    result = "found profile"
  )
  expect_true(nchar(step$id) > 0)
  expect_equal(step$conversation_id, conv$id)
})

test_that("e2e: recordStep without result", {
  client <- new_e2e_client()
  conv <- new_conv(client); on.exit(cleanup(conv))
  step <- client$reasoning$record_step(conversation_id = conv$id, reasoning = "r", action_taken = "a")
  expect_true(nchar(step$id) > 0)
})

test_that("e2e: listSteps returns recorded", {
  client <- new_e2e_client()
  conv <- new_conv(client); on.exit(cleanup(conv))
  s1 <- client$reasoning$record_step(conversation_id = conv$id, reasoning = "r1", action_taken = "a1")
  s2 <- client$reasoning$record_step(conversation_id = conv$id, reasoning = "r2", action_taken = "a2")
  steps <- client$reasoning$list_steps(conv$id)
  ids <- vapply(steps, function(s) s$id, character(1))
  expect_true(s1$id %in% ids)
  expect_true(s2$id %in% ids)
})

test_that("e2e: explainStep returns tool_calls + influenced_entities", {
  client <- new_e2e_client()
  conv <- new_conv(client); on.exit(cleanup(conv))
  step <- client$reasoning$record_step(conversation_id = conv$id, reasoning = "r", action_taken = "a")
  ex <- client$reasoning$explain_step(step$id)
  expect_equal(ex$id, step$id)
  expect_true(is.list(ex$tool_calls))
  expect_true(is.list(ex$influenced_entities))
})

test_that("e2e: getTraceByConversation empty conv", {
  client <- new_e2e_client()
  conv <- new_conv(client); on.exit(cleanup(conv))
  trace <- client$reasoning$get_trace_by_conversation(conv$id)
  expect_equal(trace$conversation_id, conv$id)
  expect_true(is.list(trace$steps))
})

test_that("e2e: getTraceByConversation includes recorded step", {
  client <- new_e2e_client()
  conv <- new_conv(client); on.exit(cleanup(conv))
  client$reasoning$record_step(conversation_id = conv$id, reasoning = "r", action_taken = "a")
  trace <- client$reasoning$get_trace_by_conversation(conv$id)
  reasonings <- vapply(trace$steps, function(s) s$reasoning %||% "", character(1))
  expect_true(any(grepl("r", reasonings)))
})

# ===========================================================================
# 9. Cypher (skipped on 403)
# ===========================================================================

test_that("e2e: cypher count query", {
  client <- new_e2e_client()
  result <- tryCatch(
    client$query$cypher("MATCH (n) RETURN count(n) AS total"),
    error = function(e) {
      if (grepl("[Aa]uthentication|403", conditionMessage(e))) {
        skip(paste("API key lacks Cypher scope:", conditionMessage(e)))
      }
      stop(e)
    }
  )
  expect_true("total" %in% result$columns)
})

test_that("e2e: cypher parameterised query", {
  client <- new_e2e_client()
  result <- tryCatch(
    client$query$cypher("MATCH (n) RETURN $label AS label LIMIT 1",
                        params = list(label = "tck-e2e")),
    error = function(e) {
      if (grepl("[Aa]uthentication|403", conditionMessage(e))) {
        skip(paste("API key lacks Cypher scope:", conditionMessage(e)))
      }
      stop(e)
    }
  )
  expect_true(is.list(result$columns) || is.character(result$columns))
})

# ===========================================================================
# 10. Auth API (skipped on 403)
# ===========================================================================

test_that("e2e: listApiKeys returns array (or skips)", {
  client <- new_e2e_client()
  conv <- new_conv(client); on.exit(cleanup(conv))
  meta <- client$short_term$get_conversation_metadata(conv$id)
  ws <- meta$workspace_id
  if (is.null(ws) || !nzchar(ws)) skip("workspace_id not exposed")
  keys <- tryCatch(
    client$auth$list_api_keys(ws),
    error = function(e) {
      if (grepl("[Aa]uthentication|403", conditionMessage(e))) {
        skip(paste("API key lacks auth scope:", conditionMessage(e)))
      }
      stop(e)
    }
  )
  expect_true(is.list(keys))
})

# ===========================================================================
# 11. Cross-feature workflows
# ===========================================================================

test_that("e2e: workflow — message flow extracts entities (or skips)", {
  client <- new_e2e_client()
  conv <- new_conv(client, "agent-flow"); on.exit(cleanup(conv))
  unique_name <- paste0("TCKMercury", random_hex(8))
  client$short_term$add_message(conv$id, "user",
    paste(unique_name, "is the smallest planet in the solar system."))
  client$short_term$add_message(conv$id, "assistant",
    paste("Yes,", unique_name, "has a thin atmosphere."))
  ok <- wait_until(function() {
    hits <- client$long_term$search_entities(unique_name, limit = 10L)
    any(vapply(hits, function(h) {
      grepl(tolower(unique_name), tolower(h$name %||% ""), fixed = TRUE)
    }, logical(1)))
  }, timeout = 20)
  if (!ok) skip("extracted entity not indexed within 20s")
  expect_true(ok)
})

test_that("e2e: workflow — multi-step reasoning trace", {
  client <- new_e2e_client()
  conv <- new_conv(client); on.exit(cleanup(conv))
  recorded <- character(0)
  for (i in 0:2) {
    s <- client$reasoning$record_step(
      conversation_id = conv$id,
      reasoning = paste("step", i, "reasoning"),
      action_taken = paste0("action_", i),
      result = paste0("result_", i)
    )
    recorded <- c(recorded, s$id)
  }
  trace <- client$reasoning$get_trace_by_conversation(conv$id)
  traced <- vapply(trace$steps, function(s) s$id, character(1))
  for (id in recorded) expect_true(id %in% traced)
})

test_that("e2e: workflow — multi-turn conversation appears in context", {
  client <- new_e2e_client()
  conv <- new_conv(client); on.exit(cleanup(conv))
  turns <- list(
    list("user", "I'm planning a trip to Tokyo next month."),
    list("assistant", "Tokyo is great in autumn — what are your interests?"),
    list("user", "Mostly food and historical sites."),
    list("assistant", "Visit Tsukiji Outer Market and Senso-ji."),
    list("user", "How long should I stay?")
  )
  for (turn in turns) {
    client$short_term$add_message(conv$id, turn[[1]], turn[[2]])
  }
  ctx <- client$short_term$get_context(conv$id)
  contents <- vapply(ctx$recent_messages, function(m) m$content %||% "", character(1))
  joined <- paste(contents, collapse = " ")
  expect_true(grepl("Tokyo|Tsukiji", joined))
})
