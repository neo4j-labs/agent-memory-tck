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
      private$transport$request("clear_session", list(session_id = session_id))
      invisible(NULL)
    },

    # ---- Volume 5 / hosted-native ----------------------------------------

    create_conversation = function(user_id, metadata = NULL) {
      result <- private$transport$request("create_conversation", list(
        user_id = user_id,
        metadata = metadata
      ))
      parse_conversation(result)
    },

    list_conversations = function(limit = NULL) {
      result <- private$transport$request("list_conversations", list(
        limit = if (!is.null(limit)) as.integer(limit) else NULL
      ))
      if (is.null(result)) return(list())
      lapply(result, parse_conversation)
    },

    get_conversation_metadata = function(conversation_id) {
      result <- private$transport$request("get_conversation_metadata", list(
        conversation_id = as.character(conversation_id)
      ))
      parse_conversation(result)
    },

    delete_conversation = function(conversation_id) {
      private$transport$request("delete_conversation", list(
        conversation_id = as.character(conversation_id)
      ))
      invisible(NULL)
    },

    get_context = function(conversation_id) {
      result <- private$transport$request("get_context", list(
        conversation_id = as.character(conversation_id)
      ))
      parse_context(result)
    },

    bulk_add_messages = function(conversation_id, messages) {
      if (length(messages) > 100) stop("bulk_add_messages: max 100 messages")
      result <- private$transport$request("bulk_add_messages", list(
        conversation_id = as.character(conversation_id),
        messages = messages
      ))
      if (is.null(result)) return(list())
      lapply(result, parse_message)
    },

    get_observations = function(conversation_id, limit = NULL) {
      result <- private$transport$request("get_observations", list(
        conversation_id = as.character(conversation_id),
        limit = if (!is.null(limit)) as.integer(limit) else NULL
      ))
      if (is.null(result)) return(list())
      lapply(result, parse_observation)
    },

    get_reflections = function(conversation_id) {
      result <- private$transport$request("get_reflections", list(
        conversation_id = as.character(conversation_id)
      ))
      if (is.null(result)) return(list())
      lapply(result, parse_reflection)
    }
  ),

  private = list(transport = NULL)
)
