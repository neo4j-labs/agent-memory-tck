#' @title Short-Term Memory Client
#' @description Manages conversation history: messages, sessions, search.
#' @export
ShortTermMemory <- R6::R6Class("ShortTermMemory",
  public = list(
    initialize = function(transport) {
      private$transport <- transport
    },

    add_message = function(session_id, role, content, metadata = NULL) {
      result <- private$transport$request("add_message", list(
        session_id = session_id,
        role = role,
        content = content,
        metadata = metadata
      ))
      parse_message(result)
    },

    get_conversation = function(session_id, limit = NULL) {
      result <- private$transport$request("get_conversation", list(
        session_id = session_id,
        limit = if (!is.null(limit)) as.integer(limit) else NULL
      ))
      parse_conversation(result)
    },

    search_messages = function(query, session_id = NULL, limit = 10L, threshold = 0.7) {
      result <- private$transport$request("search_messages", list(
        query = query,
        session_id = session_id,
        limit = as.integer(limit),
        threshold = threshold
      ))
      if (is.null(result)) return(list())
      lapply(result, parse_message)
    },

    list_sessions = function(limit = 100L) {
      result <- private$transport$request("list_sessions", list(
        limit = as.integer(limit)
      ))
      if (is.null(result)) return(list())
      lapply(result, parse_session_info)
    },

    delete_message = function(message_id) {
      result <- private$transport$request("delete_message", list(
        message_id = as.character(message_id)
      ))
      isTRUE(result$deleted)
    },

    clear_session = function(session_id) {
      private$transport$request("clear_session", list(
        session_id = session_id
      ))
      invisible(NULL)
    }
  ),

  private = list(
    transport = NULL
  )
)
