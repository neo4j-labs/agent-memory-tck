# Mock transport that records calls and returns pre-configured responses.
# Used by sub-client unit tests to verify correct method/param dispatch
# without requiring a live server.

MockTransport <- R6::R6Class("MockTransport",
  public = list(
    calls = NULL,
    responses = NULL,

    initialize = function() {
      self$calls <- list()
      self$responses <- list()
    },

    set_response = function(method, response) {
      self$responses[[method]] <- response
    },

    request = function(method, params = list()) {
      params <- params[!vapply(params, is.null, logical(1))]
      self$calls[[length(self$calls) + 1]] <- list(method = method, params = params)
      self$responses[[method]]
    },

    connect = function() invisible(self),
    close = function() invisible(NULL),

    last_call = function() {
      if (length(self$calls) == 0) return(NULL)
      self$calls[[length(self$calls)]]
    },

    call_count = function(method = NULL) {
      if (is.null(method)) return(length(self$calls))
      sum(vapply(self$calls, function(c) c$method == method, logical(1)))
    }
  )
)
