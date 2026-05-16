#' @title RestTransport — hosted Neo4j Agent Memory REST API
#'
#' @description
#' Speaks the hosted REST API at `https://memory.neo4jlabs.com/v1`. Maps
#' bridge-style `request(method, params)` calls to REST endpoints with
#' snake_case ↔ camelCase translation. Methods that have no REST equivalent
#' raise a "method not supported by RestTransport" error.
#'
#' @export
RestTransport <- R6::R6Class("RestTransport",
  public = list(
    initialize = function(endpoint, api_key = NULL, timeout = 30,
                          token_provider = NULL, headers = NULL) {
      private$endpoint <- sub("/+$", "", endpoint)
      private$api_key <- api_key
      private$timeout <- timeout
      private$token_provider <- token_provider
      private$headers <- if (is.null(headers)) list() else headers
    },

    connect = function() {
      url <- paste0(private$endpoint, "/conversations?limit=1")
      req <- httr2::request(url) |>
        httr2::req_method("GET") |>
        httr2::req_timeout(private$timeout)
      req <- private$apply_auth(req, has_body = FALSE)

      resp <- tryCatch(
        req |> httr2::req_error(is_error = function(r) FALSE) |> httr2::req_perform(),
        error = function(e) NULL
      )
      if (is.null(resp)) return(invisible(self))
      status <- httr2::resp_status(resp)
      if (status == 401L || status == 403L) {
        stop(sprintf("Authentication failed: %d", status), call. = FALSE)
      }
      invisible(self)
    },

    close = function() invisible(NULL),

    request = function(method, params = list()) {
      params <- if (is.null(params)) list() else params
      params <- params[!vapply(params, is.null, logical(1))]
      route <- private$routes[[method]]
      if (is.null(route)) {
        stop(sprintf("Method '%s' is not supported by RestTransport", method),
             call. = FALSE)
      }
      if (identical(route$kind, "noop")) return(NULL)
      if (identical(route$kind, "unsupported")) {
        stop(sprintf("Method '%s' has no equivalent in the hosted REST API",
                     method), call. = FALSE)
      }

      camel <- snake_to_camel(params)
      consumed <- character(0)

      path <- route$path
      for (name in route$path_params %||% character(0)) {
        v <- camel[[name]]
        if (is.null(v) || (is.character(v) && nchar(v) == 0)) {
          stop(sprintf("Missing path param '%s' for method '%s'", name, method),
               call. = FALSE)
        }
        path <- gsub(paste0("\\{", name, "\\}"), utils::URLencode(as.character(v),
          reserved = TRUE), path)
        consumed <- c(consumed, name)
      }

      # Query params on the hosted REST API are snake_case
      # (conversation_id, workspace_id, etc.), NOT camelCase. Look up from
      # the original snake-cased params first; fall back to the camel form
      # if the caller passed it that way. Mark both as consumed so neither
      # leaks into the body.
      qs_pairs <- character(0)
      for (name in route$query_params %||% character(0)) {
        v <- params[[name]]
        if (is.null(v)) {
          v <- camel[[snake_to_camel_key(name)]]
        }
        if (!is.null(v)) {
          qs_pairs <- c(qs_pairs,
            paste0(name, "=", utils::URLencode(as.character(v), reserved = TRUE)))
          consumed <- c(consumed, name, snake_to_camel_key(name))
        }
      }
      qs <- if (length(qs_pairs)) paste0("?", paste(qs_pairs, collapse = "&")) else ""

      url <- paste0(private$endpoint, path, qs)
      req <- httr2::request(url) |>
        httr2::req_method(route$method) |>
        httr2::req_timeout(private$timeout)

      if (isTRUE(route$has_body)) {
        body <- camel[setdiff(names(camel), consumed)]
        req <- req |>
          httr2::req_headers("Content-Type" = "application/json") |>
          httr2::req_body_json(body, auto_unbox = TRUE)
      }

      req <- private$apply_auth(req, has_body = isTRUE(route$has_body))

      resp <- req |>
        httr2::req_error(is_error = function(r) FALSE) |>
        httr2::req_perform()

      status <- httr2::resp_status(resp)
      if (status == 401L || status == 403L) {
        stop(sprintf("Authentication failed: %d", status), call. = FALSE)
      }
      if (status == 204L) return(invisible(NULL))

      body_str <- tryCatch(httr2::resp_body_string(resp), error = function(e) "")
      if (status >= 400L) {
        stop(sprintf("HTTP %d: %s", status, body_str), call. = FALSE)
      }
      if (nchar(body_str) == 0) return(NULL)

      raw <- httr2::resp_body_json(resp, simplifyVector = FALSE)
      if (!is.null(route$shape)) raw <- route$shape(raw, camel)
      camel_to_snake(raw)
    }
  ),

  private = list(
    endpoint = NULL,
    api_key = NULL,
    token_provider = NULL,
    timeout = 30,
    headers = list(),

    apply_auth = function(req, has_body) {
      for (name in names(private$headers)) {
        req <- httr2::req_headers(req, !!name := private$headers[[name]])
      }
      token <- private$api_key
      if (!is.null(private$token_provider)) {
        token <- private$token_provider()
      }
      if (!is.null(token) && nzchar(token)) {
        req <- httr2::req_headers(req, Authorization = paste("Bearer", token))
      }
      req
    },

    routes = list(
      setup = list(kind = "noop"),
      teardown = list(kind = "noop"),
      clear_all_data = list(kind = "noop"),

      add_message = list(method = "POST", path = "/conversations/{sessionId}/messages",
        path_params = c("sessionId"), has_body = TRUE),
      get_conversation = list(method = "GET",
        path = "/conversations/{sessionId}/messages",
        path_params = c("sessionId"), query_params = c("limit"),
        shape = function(raw, p) {
          messages <- raw
          if (is.list(raw) && !is.null(raw$messages)) messages <- raw$messages
          list(id = p$sessionId, sessionId = p$sessionId, messages = messages)
        }),
      list_sessions = list(method = "GET", path = "/conversations",
        query_params = c("limit"),
        shape = function(raw, p) {
          conv <- if (is.list(raw) && !is.null(raw$conversations)) raw$conversations else list()
          lapply(conv, function(c) list(
            sessionId = c$id,
            messageCount = c$messageCount %||% 0L,
            createdAt = c$createdAt,
            updatedAt = c$updatedAt
          ))
        }),
      search_messages = list(method = "POST",
        path = "/conversations/{sessionId}/search",
        path_params = c("sessionId"), has_body = TRUE,
        shape = function(raw, p) {
          if (is.list(raw) && !is.null(raw$messages)) raw$messages else raw
        }),
      clear_session = list(method = "DELETE",
        path = "/conversations/{sessionId}",
        path_params = c("sessionId")),
      delete_message = list(kind = "unsupported"),

      add_entity = list(method = "POST", path = "/entities", has_body = TRUE),
      search_entities = list(method = "POST", path = "/entities/search",
        has_body = TRUE,
        shape = function(raw, p) {
          if (is.list(raw) && !is.null(raw$entities)) raw$entities else raw
        }),
      add_preference = list(kind = "unsupported"),
      add_fact = list(kind = "unsupported"),
      search_preferences = list(kind = "unsupported"),
      get_entity_by_name = list(kind = "unsupported"),
      get_related_entities = list(kind = "unsupported"),
      add_relationship = list(kind = "unsupported"),
      merge_duplicate_entities = list(kind = "unsupported"),

      start_trace = list(kind = "unsupported"),
      add_step = list(kind = "unsupported"),
      record_tool_call = list(method = "POST", path = "/reasoning/tool-calls",
        has_body = TRUE),
      complete_trace = list(kind = "unsupported"),
      get_trace_with_steps = list(kind = "unsupported"),
      list_traces = list(kind = "unsupported"),
      get_tool_stats = list(kind = "unsupported"),
      get_similar_traces = list(kind = "unsupported"),

      # ---- Volume 5 / hosted-native ---------------------------------------
      create_conversation = list(method = "POST", path = "/conversations",
        has_body = TRUE),
      list_conversations = list(method = "GET", path = "/conversations",
        query_params = c("limit"),
        shape = function(raw, p) {
          if (is.list(raw) && !is.null(raw$conversations)) raw$conversations else raw
        }),
      get_conversation_metadata = list(method = "GET",
        path = "/conversations/{conversationId}",
        path_params = c("conversationId")),
      delete_conversation = list(method = "DELETE",
        path = "/conversations/{conversationId}",
        path_params = c("conversationId")),
      get_context = list(method = "GET",
        path = "/conversations/{conversationId}/context",
        path_params = c("conversationId")),
      bulk_add_messages = list(method = "POST",
        path = "/conversations/{conversationId}/messages/bulk",
        path_params = c("conversationId"), has_body = TRUE,
        shape = function(raw, p) {
          if (is.list(raw) && !is.null(raw$messages)) raw$messages else raw
        }),
      get_observations = list(method = "GET",
        path = "/conversations/{conversationId}/observations",
        path_params = c("conversationId"), query_params = c("limit"),
        shape = function(raw, p) {
          if (is.list(raw) && !is.null(raw$observations)) raw$observations else raw
        }),
      get_reflections = list(method = "GET",
        path = "/conversations/{conversationId}/reflections",
        path_params = c("conversationId"),
        shape = function(raw, p) {
          if (is.list(raw) && !is.null(raw$reflections)) raw$reflections else raw
        }),

      list_entities = list(method = "GET", path = "/entities",
        query_params = c("type", "limit"),
        shape = function(raw, p) {
          if (is.list(raw) && !is.null(raw$entities)) raw$entities else raw
        }),
      get_entity = list(method = "GET", path = "/entities/{entityId}",
        path_params = c("entityId")),
      update_entity = list(method = "PUT", path = "/entities/{entityId}",
        path_params = c("entityId"), has_body = TRUE),
      delete_entity = list(method = "DELETE", path = "/entities/{entityId}",
        path_params = c("entityId")),
      set_entity_feedback = list(method = "PUT",
        path = "/entities/{entityId}/feedback",
        path_params = c("entityId"), has_body = TRUE),
      get_entity_history = list(method = "GET",
        path = "/entities/{entityId}/history",
        path_params = c("entityId")),
      merge_entities = list(method = "POST",
        path = "/entities/{sourceId}/merge",
        path_params = c("sourceId"), has_body = TRUE),
      get_entity_graph = list(method = "GET", path = "/entities/graph"),

      explain_step = list(method = "GET",
        path = "/reasoning/explain/{stepId}",
        path_params = c("stepId")),
      get_trace_by_conversation = list(method = "GET",
        path = "/reasoning/trace/{conversationId}",
        path_params = c("conversationId")),
      get_entity_provenance = list(method = "GET",
        path = "/reasoning/provenance/{entityId}",
        path_params = c("entityId")),
      record_step = list(method = "POST", path = "/reasoning/steps",
        has_body = TRUE),
      list_steps = list(method = "GET", path = "/reasoning/steps",
        query_params = c("conversation_id"),
        shape = function(raw, p) {
          if (is.list(raw) && !is.null(raw$steps)) raw$steps else raw
        }),
      cypher_query = list(method = "POST", path = "/query", has_body = TRUE),

      list_api_keys = list(method = "GET", path = "/auth/api-keys",
        query_params = c("workspace_id"),
        shape = function(raw, p) {
          if (is.list(raw) && !is.null(raw$keys)) return(raw$keys)
          if (is.list(raw) && !is.null(raw$api_keys)) return(raw$api_keys)
          raw
        }),
      create_api_key = list(method = "POST", path = "/auth/api-keys",
        has_body = TRUE),
      revoke_api_key = list(method = "DELETE", path = "/auth/api-keys/{keyId}",
        path_params = c("keyId")),
      reveal_api_key = list(method = "GET",
        path = "/auth/api-keys/{keyId}/reveal",
        path_params = c("keyId"), query_params = c("workspace_id")),
      refresh_access_token = list(method = "POST", path = "/auth/refresh",
        has_body = TRUE)
    )
  )
)
