library(plumber)
library(jsonlite)

source("memory_helpers.R")
source("tools.R")

#* @get /health
function() {
  list(
    agent = "Rune",
    framework = "ellmer",
    language = "R",
    status = "healthy"
  )
}

#* @post /analyze
function(req, res) {
  body <- req$body
  analysis_type <- body$analysis_type %||% "summary"
  entity_names <- body$entity_names %||% list()
  session_id <- paste0("rune-", format(Sys.time(), "%Y%m%d%H%M%S"))

  tryCatch({
    entities <- list()
    for (name in entity_names) {
      found <- memory_search_entities(as.character(name), limit = 5L)
      if (!is.null(found)) entities <- c(entities, found)
    }

    trace <- memory_start_trace(session_id, paste("Statistical analysis:", analysis_type))

    memory_add_message(session_id, "user",
      sprintf("Analyze entities [%s] using %s", paste(entity_names, collapse = ", "), analysis_type))

    step <- memory_add_step(trace$id,
      thought = sprintf("Running %s on %d entities", analysis_type, length(entities)),
      action = analysis_type)

    start_time <- proc.time()[[3]]

    result <- switch(analysis_type,
      "regression" = run_regression(
        entities, body$x_property %||% "value", body$y_property %||% "score"),
      "correlation" = run_correlation(
        entities, body$property1 %||% "value", body$property2 %||% "score",
        body$method %||% "pearson"),
      "clustering" = run_clustering(
        entities, body$properties %||% list("value"), as.integer(body$k %||% 3L)),
      "summary" = run_summary(
        entities, body$property %||% "value"),
      list(error = sprintf("Unknown analysis_type: %s", analysis_type))
    )

    duration <- as.integer((proc.time()[[3]] - start_time) * 1000)

    tool_status <- if (is.null(result$error)) "success" else "failure"
    memory_record_tool_call(
      step$id, analysis_type,
      arguments = list(entity_count = length(entities), type = analysis_type),
      result = result,
      status = tool_status,
      duration_ms = duration,
      error = result$error
    )

    outcome_msg <- if (is.null(result$error)) {
      sprintf("Completed %s analysis on %d entities", analysis_type, length(entities))
    } else {
      sprintf("Analysis failed: %s", result$error)
    }

    memory_complete_trace(trace$id,
      outcome = outcome_msg,
      success = is.null(result$error))

    memory_add_message(session_id, "assistant", outcome_msg)

    list(
      session_id = session_id,
      trace_id = trace$id,
      analysis_type = analysis_type,
      entity_count = length(entities),
      result = result
    )
  }, error = function(e) {
    res$status <- 500L
    list(error = conditionMessage(e))
  })
}

#* @post /query
function(req, res) {
  body <- req$body
  query <- body$query %||% ""
  limit <- as.integer(body$limit %||% 20L)

  tryCatch({
    entities <- query_entities(query, limit)
    list(
      query = query,
      entities = entities,
      count = length(entities)
    )
  }, error = function(e) {
    res$status <- 500L
    list(error = conditionMessage(e))
  })
}

`%||%` <- function(x, y) if (is.null(x)) y else x
