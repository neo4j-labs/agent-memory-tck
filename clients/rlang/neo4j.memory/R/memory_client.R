#' @title MemoryClient — root entry point
#'
#' @description
#' Picks the transport automatically based on the endpoint shape:
#'
#' * REST when the endpoint contains a `/v1` segment (hosted service).
#' * Bridge otherwise (TCK conformance servers, local reference).
#'
#' Override with `transport_mode = "bridge"` or `"rest"`.
#'
#' @export
MemoryClient <- R6::R6Class("MemoryClient",
  public = list(
    short_term = NULL,
    long_term = NULL,
    reasoning = NULL,
    query = NULL,
    auth = NULL,

    #' @param endpoint Service URL (`http://localhost:3001` or
    #'   `https://memory.neo4jlabs.com/v1`).
    #' @param api_key Bearer API key (e.g. `nams_*`).
    #' @param transport Pre-built transport instance — overrides endpoint.
    #' @param transport_mode "auto", "bridge", or "rest".
    #' @param token_provider Optional function returning a fresh token (REST).
    #' @param headers Named list of additional headers.
    #' @param timeout Request timeout in seconds (default 30).
    initialize = function(endpoint = NULL, api_key = NULL, transport = NULL,
                          transport_mode = "auto",
                          token_provider = NULL, headers = NULL,
                          timeout = 30) {
      if (!is.null(transport)) {
        private$transport <- transport
      } else if (!is.null(endpoint)) {
        mode <- transport_mode
        if (mode == "auto") {
          mode <- if (grepl("/v[0-9]+(/|$)", endpoint)) "rest" else "bridge"
        }
        private$transport <- if (mode == "rest") {
          RestTransport$new(endpoint, api_key = api_key, timeout = timeout,
            token_provider = token_provider, headers = headers)
        } else {
          BridgeTransport$new(endpoint, api_key = api_key, timeout = timeout,
            headers = headers)
        }
      } else {
        stop("Either 'endpoint' or 'transport' must be provided", call. = FALSE)
      }

      self$short_term <- ShortTermMemory$new(private$transport)
      self$long_term <- LongTermMemory$new(private$transport)
      self$reasoning <- ReasoningMemory$new(private$transport)
      self$query <- QueryConsole$new(private$transport)
      self$auth <- AuthClient$new(private$transport)
    },

    connect = function() { private$transport$connect(); invisible(self) },
    close = function() { private$transport$close(); invisible(NULL) },

    clear_all_data = function() {
      tryCatch(private$transport$request("clear_all_data"),
        error = function(e) NULL)
      invisible(NULL)
    }
  ),

  private = list(transport = NULL)
)
