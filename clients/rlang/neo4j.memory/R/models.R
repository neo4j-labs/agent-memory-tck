#' @title Parse helpers for wire-format JSON responses
#' @name parse_helpers
NULL

#' @export
parse_message <- function(data) {
  if (is.null(data)) return(NULL)
  list(
    id = data$id,
    role = data$role,
    content = data$content,
    timestamp = data$timestamp,
    embedding = data$embedding,
    metadata = if (is.null(data$metadata)) list() else data$metadata
  )
}

#' @export
parse_conversation <- function(data) {
  if (is.null(data)) return(NULL)
  msgs <- if (is.null(data$messages)) list() else lapply(data$messages, parse_message)
  list(
    id = data$id,
    session_id = data$session_id,
    messages = msgs,
    title = data$title,
    created_at = data$created_at,
    updated_at = data$updated_at
  )
}

#' @export
parse_session_info <- function(data) {
  if (is.null(data)) return(NULL)
  list(
    session_id = data$session_id,
    message_count = as.integer(data$message_count %||% 0L),
    created_at = data$created_at,
    updated_at = data$updated_at
  )
}

#' @export
parse_entity <- function(data) {
  if (is.null(data)) return(NULL)
  list(
    id = data$id,
    name = data$name,
    type = data$type,
    subtype = data$subtype,
    description = data$description,
    embedding = data$embedding,
    canonical_name = data$canonical_name,
    created_at = data$created_at
  )
}

#' @export
parse_preference <- function(data) {
  if (is.null(data)) return(NULL)
  list(
    id = data$id,
    category = data$category,
    preference = data$preference,
    context = data$context,
    embedding = data$embedding
  )
}

#' @export
parse_fact <- function(data) {
  if (is.null(data)) return(NULL)
  list(
    id = data$id,
    subject = data$subject,
    predicate = data$predicate,
    object = data$object,
    embedding = data$embedding
  )
}

#' @export
parse_relationship <- function(data) {
  if (is.null(data)) return(NULL)
  list(
    id = data$id,
    source_id = data$source_id,
    target_id = data$target_id,
    relationship_type = data$relationship_type,
    properties = if (is.null(data$properties)) list() else data$properties
  )
}

#' @export
parse_trace <- function(data) {
  if (is.null(data)) return(NULL)
  steps <- if (is.null(data$steps)) list() else lapply(data$steps, parse_step)
  list(
    id = data$id,
    session_id = data$session_id,
    task = data$task,
    steps = steps,
    outcome = data$outcome,
    success = data$success,
    started_at = data$started_at,
    completed_at = data$completed_at
  )
}

#' @export
parse_step <- function(data) {
  if (is.null(data)) return(NULL)
  tool_calls <- if (is.null(data$tool_calls)) list() else lapply(data$tool_calls, parse_tool_call)
  list(
    id = data$id,
    trace_id = data$trace_id,
    step_number = as.integer(data$step_number %||% 0L),
    thought = data$thought,
    action = data$action,
    observation = data$observation,
    tool_calls = tool_calls
  )
}

#' @export
parse_tool_call <- function(data) {
  if (is.null(data)) return(NULL)
  list(
    id = data$id,
    tool_name = data$tool_name,
    arguments = if (is.null(data$arguments)) list() else data$arguments,
    result = data$result,
    status = data$status,
    duration_ms = if (!is.null(data$duration_ms)) as.integer(data$duration_ms) else NULL,
    error = data$error
  )
}

#' @export
parse_tool_stats <- function(data) {
  if (is.null(data)) return(NULL)
  list(
    name = data$name,
    total_calls = as.integer(data$total_calls %||% 0L),
    successful_calls = as.integer(data$successful_calls %||% 0L),
    failed_calls = as.integer(data$failed_calls %||% 0L),
    success_rate = as.double(data$success_rate %||% 0),
    avg_duration_ms = if (!is.null(data$avg_duration_ms)) as.double(data$avg_duration_ms) else NULL
  )
}

`%||%` <- function(x, y) if (is.null(x)) y else x
