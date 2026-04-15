#' @title Reasoning Memory Client
#' @description Manages reasoning traces, steps, and tool calls.
#' @export
ReasoningMemory <- R6::R6Class("ReasoningMemory",
  public = list(
    initialize = function(transport) {
      private$transport <- transport
    },

    start_trace = function(session_id, task) {
      result <- private$transport$request("start_trace", list(
        session_id = session_id,
        task = task
      ))
      parse_trace(result)
    },

    add_step = function(trace_id, thought = NULL, action = NULL, observation = NULL) {
      result <- private$transport$request("add_step", list(
        trace_id = as.character(trace_id),
        thought = thought,
        action = action,
        observation = observation
      ))
      parse_step(result)
    },

    record_tool_call = function(step_id, tool_name, arguments,
                                result = NULL, status = "success",
                                duration_ms = NULL, error = NULL) {
      resp <- private$transport$request("record_tool_call", list(
        step_id = as.character(step_id),
        tool_name = tool_name,
        arguments = arguments,
        result = result,
        status = status,
        duration_ms = if (!is.null(duration_ms)) as.integer(duration_ms) else NULL,
        error = error
      ))
      parse_tool_call(resp)
    },

    complete_trace = function(trace_id, outcome = NULL, success = NULL) {
      result <- private$transport$request("complete_trace", list(
        trace_id = as.character(trace_id),
        outcome = outcome,
        success = success
      ))
      parse_trace(result)
    },

    get_trace_with_steps = function(trace_id) {
      result <- private$transport$request("get_trace_with_steps", list(
        trace_id = as.character(trace_id)
      ))
      if (is.null(result)) return(NULL)
      parse_trace(result)
    },

    list_traces = function(session_id = NULL, limit = 100L) {
      result <- private$transport$request("list_traces", list(
        session_id = session_id,
        limit = as.integer(limit)
      ))
      if (is.null(result)) return(list())
      lapply(result, parse_trace)
    },

    get_tool_stats = function(tool_name = NULL) {
      result <- private$transport$request("get_tool_stats", list(
        tool_name = tool_name
      ))
      if (is.null(result)) return(list())
      lapply(result, parse_tool_stats)
    },

    get_similar_traces = function(task, limit = 5L, success_only = TRUE) {
      result <- private$transport$request("get_similar_traces", list(
        task = task,
        limit = as.integer(limit),
        success_only = success_only
      ))
      if (is.null(result)) return(list())
      lapply(result, parse_trace)
    }
  ),

  private = list(
    transport = NULL
  )
)
