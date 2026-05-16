#' @title ellmer integration — register the 12 memory tools as ellmer tools
#'
#' @description
#' Returns a list of `ellmer::tool()` definitions wrapping each MCP tool. Pass
#' the result to your ellmer `Chat` instance via `chat$register_tool()`.
#'
#' @param client A `MemoryClient` instance.
#'
#' @export
register_memory_tools <- function(client) {
  tools <- mcp_tools()
  lapply(tools, function(spec) {
    list(
      name = spec$name,
      description = spec$description,
      input_schema = spec$inputSchema,
      handler = function(args) mcp_dispatch(client, spec$name, args)
    )
  })
}
