# Walks up from the package root looking for a `.env` file and loads each
# `KEY=VALUE` line into the process environment. Used by e2e tests to pick
# up MEMORY_API_KEY locally; CI sets the variable directly so this is a
# no-op there.

load_dotenv_walking_up <- function() {
  dir <- normalizePath(getwd(), mustWork = FALSE)
  for (i in seq_len(8)) {
    candidate <- file.path(dir, ".env")
    if (file.exists(candidate)) {
      lines <- readLines(candidate, warn = FALSE)
      for (line in lines) {
        line <- trimws(line)
        if (nchar(line) == 0 || startsWith(line, "#")) next
        eq <- regexpr("=", line, fixed = TRUE)
        if (eq < 1) next
        key <- substring(line, 1, eq - 1)
        val <- substring(line, eq + 1)
        # Strip surrounding quotes
        if (nchar(val) >= 2 && substr(val, 1, 1) == "\"" &&
            substr(val, nchar(val), nchar(val)) == "\"") {
          val <- substr(val, 2, nchar(val) - 1)
        }
        if (Sys.getenv(key, "") == "") {
          do.call(Sys.setenv, setNames(list(val), key))
        }
      }
      return(invisible(candidate))
    }
    parent <- dirname(dir)
    if (parent == dir) break
    dir <- parent
  }
  invisible(NULL)
}

load_dotenv_walking_up()

skip_if_no_api_key <- function() {
  if (nchar(Sys.getenv("MEMORY_API_KEY", "")) == 0) {
    testthat::skip("MEMORY_API_KEY not set — skipping e2e test")
  }
}

e2e_endpoint <- function() {
  endpoint <- Sys.getenv("MEMORY_ENDPOINT", "")
  if (nchar(endpoint) == 0) endpoint <- "https://memory.neo4jlabs.com/v1"
  endpoint
}

e2e_user_id <- function() {
  base <- Sys.getenv("MEMORY_E2E_USER_ID", "tck-e2e-r")
  paste0(base, "-", paste(sample(c(letters, 0:9), 8, replace = TRUE), collapse = ""))
}
