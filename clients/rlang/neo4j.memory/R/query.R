#' @title QueryConsole — read-only Cypher query
#' @description Hosted service only. Executes read-only Cypher and returns
#'   `{ columns, rows, stats }`.
#' @export
QueryConsole <- R6::R6Class("QueryConsole",
  public = list(
    initialize = function(transport) {
      private$transport <- transport
    },

    cypher = function(cypher, params = NULL) {
      args <- list(cypher = cypher)
      # An empty R list serialises as a JSON array; the service needs an object.
      if (length(params) > 0) args$params <- params
      result <- private$transport$request("cypher_query", args)
      list(
        columns = if (is.null(result$columns)) list() else result$columns,
        rows = if (is.null(result$rows)) list() else result$rows,
        stats = result$stats
      )
    }
  ),

  private = list(transport = NULL)
)
