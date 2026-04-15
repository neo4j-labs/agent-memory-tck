#' @title Neo4j Agent Memory Client
#' @description Top-level client composing short-term, long-term, and reasoning memory.
#' @export
MemoryClient <- R6::R6Class("MemoryClient",
  public = list(
    #' @field short_term ShortTermMemory instance
    short_term = NULL,
    #' @field long_term LongTermMemory instance
    long_term = NULL,
    #' @field reasoning ReasoningMemory instance
    reasoning = NULL,

    #' @param endpoint Memory service URL (e.g. "http://localhost:3001")
    #' @param transport Optional pre-configured HttpTransport instance
    initialize = function(endpoint = NULL, transport = NULL) {
      if (!is.null(transport)) {
        private$transport <- transport
      } else if (!is.null(endpoint)) {
        private$transport <- HttpTransport$new(endpoint)
      } else {
        stop("Either 'endpoint' or 'transport' must be provided", call. = FALSE)
      }

      self$short_term <- ShortTermMemory$new(private$transport)
      self$long_term <- LongTermMemory$new(private$transport)
      self$reasoning <- ReasoningMemory$new(private$transport)
    },

    connect = function() {
      private$transport$connect()
      invisible(self)
    },

    close = function() {
      private$transport$close()
      invisible(NULL)
    },

    clear_all_data = function() {
      private$transport$request("clear_all_data")
      invisible(NULL)
    }
  ),

  private = list(
    transport = NULL
  )
)
