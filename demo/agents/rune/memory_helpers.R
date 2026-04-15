library(httr2)
library(jsonlite)

MEMORY_ENDPOINT <- Sys.getenv("MEMORY_ENDPOINT", "http://localhost:3001")

memory_request <- function(method, params = list()) {
  params <- params[!vapply(params, is.null, logical(1))]

  url <- paste0(MEMORY_ENDPOINT, "/", method)
  resp <- request(url) |>
    req_method("POST") |>
    req_headers("Content-Type" = "application/json") |>
    req_body_json(params, auto_unbox = TRUE) |>
    req_timeout(30) |>
    req_error(is_error = function(resp) FALSE) |>
    req_perform()

  status <- resp_status(resp)
  if (status == 204L) return(invisible(NULL))
  if (status >= 400L) {
    body <- tryCatch(resp_body_string(resp), error = function(e) "")
    stop(sprintf("Memory API error %d: %s", status, body), call. = FALSE)
  }

  body_str <- tryCatch(resp_body_string(resp), error = function(e) "")
  if (nchar(body_str) == 0 || body_str == "null") return(NULL)
  resp_body_json(resp)
}

memory_search_entities <- function(query, limit = 20L) {
  memory_request("search_entities", list(query = query, limit = as.integer(limit)))
}

memory_add_message <- function(session_id, role, content) {
  memory_request("add_message", list(
    session_id = session_id, role = role, content = content
  ))
}

memory_start_trace <- function(session_id, task) {
  memory_request("start_trace", list(session_id = session_id, task = task))
}

memory_add_step <- function(trace_id, thought = NULL, action = NULL, observation = NULL) {
  memory_request("add_step", list(
    trace_id = trace_id, thought = thought, action = action, observation = observation
  ))
}

memory_record_tool_call <- function(step_id, tool_name, arguments,
                                    result = NULL, status = "success",
                                    duration_ms = NULL, error = NULL) {
  memory_request("record_tool_call", list(
    step_id = step_id, tool_name = tool_name, arguments = arguments,
    result = result, status = status,
    duration_ms = if (!is.null(duration_ms)) as.integer(duration_ms) else NULL,
    error = error
  ))
}

memory_complete_trace <- function(trace_id, outcome = NULL, success = NULL) {
  memory_request("complete_trace", list(
    trace_id = trace_id, outcome = outcome, success = success
  ))
}
