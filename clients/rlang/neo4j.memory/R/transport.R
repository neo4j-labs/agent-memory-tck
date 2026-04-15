#' @title HTTP Transport for Neo4j Agent Memory
#' @description Sends JSON-over-HTTP requests to a memory service endpoint.
#' @export
HttpTransport <- R6::R6Class("HttpTransport",
  public = list(
    initialize = function(endpoint, timeout = 30) {
      private$endpoint <- sub("/+$", "", endpoint)
      private$timeout <- timeout
    },

    connect = function() {
      self$request("setup")
      invisible(self)
    },

    close = function() {
      invisible(NULL)
    },

    request = function(method, params = list()) {
      params <- params[!vapply(params, is.null, logical(1))]

      url <- paste0(private$endpoint, "/", method)
      req <- httr2::request(url) |>
        httr2::req_method("POST") |>
        httr2::req_headers("Content-Type" = "application/json") |>
        httr2::req_timeout(private$timeout)

      if (length(params) > 0) {
        req <- req |> httr2::req_body_json(params, auto_unbox = TRUE)
      } else {
        req <- req |> httr2::req_body_json(list(), auto_unbox = TRUE)
      }

      resp <- req |>
        httr2::req_error(is_error = function(resp) FALSE) |>
        httr2::req_perform()

      status <- httr2::resp_status(resp)

      if (status == 204L) {
        return(invisible(NULL))
      }

      if (status >= 400L) {
        body <- tryCatch(
          httr2::resp_body_string(resp),
          error = function(e) ""
        )
        stop(sprintf("HTTP %d: %s", status, body), call. = FALSE)
      }

      body_str <- tryCatch(
        httr2::resp_body_string(resp),
        error = function(e) ""
      )

      if (nchar(body_str) == 0 || body_str == "") {
        return(NULL)
      }

      if (body_str == "null") {
        return(NULL)
      }

      httr2::resp_body_json(resp)
    }
  ),

  private = list(
    endpoint = NULL,
    timeout = 30
  )
)
