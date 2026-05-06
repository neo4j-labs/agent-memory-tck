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
      result <- private$transport$request("cypher_query", list(
        cypher = cypher,
        params = if (is.null(params)) list() else params
      ))
      list(
        columns = if (is.null(result$columns)) list() else result$columns,
        rows = if (is.null(result$rows)) list() else result$rows,
        stats = result$stats
      )
    }
  ),

  private = list(transport = NULL)
)
