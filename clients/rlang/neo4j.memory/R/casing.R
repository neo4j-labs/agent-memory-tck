#' @title snake_case ↔ camelCase translation
#'
#' @description
#' Walks lists/objects, rewriting names from one convention to the other. Used
#' by `RestTransport` to bridge the TCK bridge protocol's snake_case calls and
#' the hosted service's camelCase API.
#' @name casing_helpers
NULL

snake_to_camel_key <- function(s) {
  parts <- strsplit(s, "_", fixed = TRUE)[[1]]
  if (length(parts) <= 1) return(s)
  paste0(parts[1], paste0(toupper(substr(parts[-1], 1, 1)),
    substr(parts[-1], 2, nchar(parts[-1])), collapse = ""))
}

camel_to_snake_key <- function(s) {
  out <- ""
  chars <- strsplit(s, "", fixed = TRUE)[[1]]
  for (i in seq_along(chars)) {
    c <- chars[i]
    if (i > 1 && c %in% LETTERS) {
      out <- paste0(out, "_", tolower(c))
    } else {
      out <- paste0(out, tolower(c))
    }
  }
  out
}

#' @export
snake_to_camel <- function(value) {
  if (is.list(value) && !is.null(names(value)) && length(names(value)) > 0) {
    out <- lapply(value, snake_to_camel)
    names(out) <- vapply(names(value), snake_to_camel_key, character(1))
    return(out)
  }
  if (is.list(value) && (is.null(names(value)) || all(names(value) == ""))) {
    return(lapply(value, snake_to_camel))
  }
  value
}

#' @export
camel_to_snake <- function(value) {
  if (is.list(value) && !is.null(names(value)) && length(names(value)) > 0) {
    out <- lapply(value, camel_to_snake)
    names(out) <- vapply(names(value), camel_to_snake_key, character(1))
    return(out)
  }
  if (is.list(value) && (is.null(names(value)) || all(names(value) == ""))) {
    return(lapply(value, camel_to_snake))
  }
  value
}
