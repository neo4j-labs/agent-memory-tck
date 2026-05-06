#' @title Reasoning Memory Client
#' @description Manages reasoning traces, steps, tool calls, and hosted
#'   provenance/explain views.
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
    },

    # ---- Volume 5 / hosted-native ----------------------------------------

    record_step = function(conversation_id, reasoning, action_taken, result = NULL) {
      resp <- private$transport$request("record_step", list(
        conversation_id = as.character(conversation_id),
        reasoning = reasoning,
        action_taken = action_taken,
        result = result
      ))
      parse_agent_step(resp)
    },

    list_steps = function(conversation_id) {
      result <- private$transport$request("list_steps", list(
        conversation_id = as.character(conversation_id)
      ))
      if (is.null(result)) return(list())
      lapply(result, parse_agent_step)
    },

    explain_step = function(step_id) {
      result <- private$transport$request("explain_step", list(
        step_id = as.character(step_id)
      ))
      parse_agent_step_explanation(result)
    },

    get_trace_by_conversation = function(conversation_id) {
      result <- private$transport$request("get_trace_by_conversation", list(
        conversation_id = as.character(conversation_id)
      ))
      list(
        conversation_id = result$conversation_id,
        steps = if (is.null(result$steps)) list() else lapply(result$steps, parse_agent_step),
        tool_calls = if (is.null(result$tool_calls)) list() else lapply(result$tool_calls, parse_tool_call)
      )
    },

    get_entity_provenance = function(entity_id) {
      result <- private$transport$request("get_entity_provenance", list(
        entity_id = as.character(entity_id)
      ))
      list(
        entity_id = result$entity_id,
        steps = if (is.null(result$steps)) list() else lapply(result$steps, parse_agent_step)
      )
    }
  ),

  private = list(transport = NULL)
)
