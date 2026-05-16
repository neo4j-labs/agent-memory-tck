# Provenance tagging for e2e tests.
#
# Every conversation, entity, and reasoning step the e2e suite creates is
# tagged with metadata that traces it back to:
#
#   - the language client            (tck_client)
#   - the specific test              (tck_test)
#   - the GitHub Actions run         (tck_run_id, tck_run_attempt)
#   - the commit SHA + branch        (tck_sha, tck_branch)
#   - the suite start time           (tck_started_at)
#   - the runner / hostname          (tck_host)
#
# Querying provenance after the fact (with workspace-admin Cypher access):
#
#   MATCH (c:Conversation) WHERE c.metadata.tck_run_id = '12345' RETURN c
#   MATCH (e:Entity) WHERE e.description STARTS WITH '[tck:r' RETURN e
#   MATCH (s:AgentStep) WHERE s.reasoning STARTS WITH 'TCK e2e' RETURN s

TCK_CLIENT_NAME <- "r"

.tck_run_info_cache <- NULL

tck_run_info <- function() {
  if (!is.null(.tck_run_info_cache)) return(.tck_run_info_cache)
  sha <- Sys.getenv("GITHUB_SHA", "local")
  if (nchar(sha) > 7) sha <- substr(sha, 1, 7)
  host <- Sys.getenv("RUNNER_NAME", "")
  if (!nzchar(host)) host <- Sys.info()[["nodename"]]
  info <- list(
    tck_client      = TCK_CLIENT_NAME,
    tck_run_id      = Sys.getenv("GITHUB_RUN_ID", "local"),
    tck_run_attempt = Sys.getenv("GITHUB_RUN_ATTEMPT", "1"),
    tck_workflow    = Sys.getenv("GITHUB_WORKFLOW", "local"),
    tck_sha         = sha,
    tck_branch      = Sys.getenv("GITHUB_REF_NAME", "local"),
    tck_started_at  = format(Sys.time(), "%Y-%m-%dT%H:%M:%S", tz = "UTC", usetz = FALSE),
    tck_host        = host
  )
  assign(".tck_run_info_cache", info, envir = topenv())
  info
}

tck_metadata_for <- function(test_name, extra = list()) {
  info <- tck_run_info()
  info$tck_test <- test_name
  for (k in names(extra)) info[[k]] <- extra[[k]]
  info
}

tck_tag_description <- function(test_name, description) {
  info <- tck_run_info()
  paste0("[tck:", info$tck_client, ":", info$tck_run_id, ":", test_name, "] ", description)
}

tck_provenance_reasoning <- function(test_name, phase = "setup") {
  info <- tck_run_info()
  sprintf(
    "TCK e2e test %s: %s [client=%s, run=%s, sha=%s, branch=%s]",
    phase, test_name, info$tck_client, info$tck_run_id, info$tck_sha, info$tck_branch
  )
}

tck_provenance_result <- function(test_name, extra = list()) {
  jsonlite::toJSON(tck_metadata_for(test_name, extra), auto_unbox = TRUE)
}

# Best-effort: record a reasoning step on the conversation tying it back to
# the originating test. Never raises — provenance failures must not mask
# real test failures.
tck_record_provenance_step <- function(client, conversation_id, test_name,
                                       phase = "setup", action = "create_conversation") {
  tryCatch(
    client$reasoning$record_step(
      conversation_id = conversation_id,
      reasoning = tck_provenance_reasoning(test_name, phase),
      action_taken = action,
      result = as.character(tck_provenance_result(
        test_name, list(conversation_id = conversation_id)
      ))
    ),
    error = function(e) NULL
  )
  invisible(NULL)
}

# Walk the call stack looking for the enclosing testthat::test_that frame
# and read its `desc` argument. Lets new_conv() / new_entity() figure out
# which test they were called from without each test having to pass the
# name explicitly. Returns "unknown" if not called from inside test_that.
tck_infer_current_test_name <- function() {
  if (sys.nframes() < 1) return("unknown")
  for (i in sys.nframes():1) {
    fn <- tryCatch(sys.function(i), error = function(e) NULL)
    if (identical(fn, testthat::test_that)) {
      env <- sys.frame(i)
      if (exists("desc", envir = env, inherits = FALSE)) {
        return(get("desc", envir = env))
      }
    }
  }
  "unknown"
}
