#' @title BridgeTransport — TCK bridge protocol HTTP transport
#'
#' @description
#' Sends bridge-style requests (`POST {endpoint}/{snake_case_method}`) with
#' snake_case JSON bodies. Used for the TCK conformance server and the local
#' reference adapter.
#'
#' @export
BridgeTransport <- R6::R6Class("BridgeTransport",
  public = list(
    initialize = function(endpoint, api_key = NULL, timeout = 30, headers = NULL) {
      private$endpoint <- sub("/+$", "", endpoint)
      private$api_key <- api_key
      private$timeout <- timeout
      private$headers <- if (is.null(headers)) list() else headers
    },

    connect = function() {
      tryCatch(self$request("setup"),
        error = function(e) NULL
      )
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

      for (name in names(private$headers)) {
        req <- httr2::req_headers(req, !!name := private$headers[[name]])
      }
      if (!is.null(private$api_key) && nzchar(private$api_key)) {
        req <- httr2::req_headers(req,
          Authorization = paste("Bearer", private$api_key))
      }

      req <- if (length(params) > 0) {
        httr2::req_body_json(req, params, auto_unbox = TRUE)
      } else {
        httr2::req_body_json(req, list(), auto_unbox = TRUE)
      }

      resp <- req |>
        httr2::req_error(is_error = function(resp) FALSE) |>
        httr2::req_perform()

      status <- httr2::resp_status(resp)

      if (status == 204L) return(invisible(NULL))
      if (status == 401L || status == 403L) {
        stop(sprintf("Authentication failed: %d", status), call. = FALSE)
      }
      if (status >= 400L) {
        body <- tryCatch(httr2::resp_body_string(resp), error = function(e) "")
        stop(sprintf("HTTP %d: %s", status, body), call. = FALSE)
      }

      body_str <- tryCatch(httr2::resp_body_string(resp), error = function(e) "")
      if (nchar(body_str) == 0 || body_str == "null") return(NULL)
      httr2::resp_body_json(resp)
    }
  ),

  private = list(
    endpoint = NULL,
    api_key = NULL,
    timeout = 30,
    headers = list()
  )
)

#' @title HttpTransport (deprecated alias for BridgeTransport)
#' @description Kept for v0.1 backwards compatibility — prefer `BridgeTransport`.
#' @export
HttpTransport <- BridgeTransport
