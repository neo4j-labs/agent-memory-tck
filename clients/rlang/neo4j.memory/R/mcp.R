#' @title MCP tool definitions matching memory.neo4jlabs.com/mcp
#'
#' @description
#' Returns the 12 standard memory tools (creation, context, search, entity
#' lookup, reasoning, explain) so an R-side MCP server can advertise the same
#' surface as the hosted service. Pair with `mcp_dispatch()` to route calls
#' through a `MemoryClient`.
#'
#' @export
mcp_tools <- function() {
  prop <- function(type, desc) list(type = type, description = desc)
  obj <- function(props, required = NULL) {
    schema <- list(type = "object", properties = props)
    if (!is.null(required)) schema$required <- required
    schema
  }

  list(
    list(name = "memory_create_conversation",
         description = "Create a new conversation session for a user.",
         inputSchema = obj(list(
           user_id = prop("string", "User identifier"),
           metadata = list(type = "object")
         ), c("user_id"))),
    list(name = "memory_add_messages",
         description = "Append one or more messages to a conversation.",
         inputSchema = obj(list(
           conversation_id = prop("string", "Conversation id"),
           messages = list(type = "array")
         ), c("conversation_id", "messages"))),
    list(name = "memory_get_context",
         description = "Three-tier context (reflections + observations + recent messages).",
         inputSchema = obj(list(
           conversation_id = prop("string", "Conversation id")
         ), c("conversation_id"))),
    list(name = "memory_search_messages",
         description = "Search messages within a conversation.",
         inputSchema = obj(list(
           conversation_id = prop("string", "Conversation id"),
           query = prop("string", "Search query"),
           limit = prop("number", "Max results")
         ), c("conversation_id", "query"))),
    list(name = "memory_search_entities",
         description = "Search the knowledge graph for entities.",
         inputSchema = obj(list(
           query = prop("string", "Query"),
           type = prop("string", "Type filter"),
           limit = prop("number", "Max")
         ), c("query"))),
    list(name = "memory_get_entity",
         description = "Fetch one entity by id.",
         inputSchema = obj(list(
           entity_id = prop("string", "Entity id")
         ), c("entity_id"))),
    list(name = "memory_add_entity",
         description = "Manually create an entity.",
         inputSchema = obj(list(
           name = prop("string", "Name"),
           type = prop("string", "Type"),
           description = prop("string", "Description")
         ), c("name", "type"))),
    list(name = "memory_get_entity_history",
         description = "All conversations that mentioned this entity.",
         inputSchema = obj(list(
           entity_id = prop("string", "Entity id")
         ), c("entity_id"))),
    list(name = "memory_record_step",
         description = "Log a reasoning step under a conversation.",
         inputSchema = obj(list(
           conversation_id = prop("string", "Conversation id"),
           reasoning = prop("string", "Reasoning"),
           action_taken = prop("string", "Action"),
           result = prop("string", "Result")
         ), c("conversation_id", "reasoning", "action_taken"))),
    list(name = "memory_record_tool_call",
         description = "Log a tool invocation tied to a reasoning step.",
         inputSchema = obj(list(
           step_id = prop("string", "Step id"),
           tool_name = prop("string", "Tool name"),
           input = prop("string", "Input"),
           output = prop("string", "Output"),
           status = prop("string", "Status"),
           duration_ms = prop("number", "Duration ms")
         ), c("tool_name", "status"))),
    list(name = "memory_get_trace",
         description = "Full reasoning trace for a conversation.",
         inputSchema = obj(list(
           conversation_id = prop("string", "Conversation id")
         ), c("conversation_id"))),
    list(name = "memory_explain_decision",
         description = "Detailed explanation of one reasoning step.",
         inputSchema = obj(list(
           step_id = prop("string", "Step id")
         ), c("step_id")))
  )
}

#' @title Dispatch one of the 12 standard MCP tools to a MemoryClient
#'
#' @param client A `MemoryClient` instance.
#' @param name Tool name (e.g. `"memory_get_context"`).
#' @param args Named list of tool arguments.
#'
#' @export
mcp_dispatch <- function(client, name, args) {
  args <- if (is.null(args)) list() else args
  switch(name,
    "memory_create_conversation" = client$short_term$create_conversation(
      args$user_id, args$metadata),
    "memory_add_messages" = mcp_dispatch_add_messages(client, args),
    "memory_get_context" = client$short_term$get_context(args$conversation_id),
    "memory_search_messages" = client$short_term$search_messages(
      args$query, args$conversation_id, args$limit %||% 10L, 0.0),
    "memory_search_entities" = client$long_term$search_entities(
      args$query, args$limit %||% 10L, args$type),
    "memory_get_entity" = client$long_term$get_entity(args$entity_id),
    "memory_add_entity" = client$long_term$add_entity(
      args$name, args$type, args$description),
    "memory_get_entity_history" = client$long_term$get_entity_history(args$entity_id),
    "memory_record_step" = client$reasoning$record_step(
      args$conversation_id, args$reasoning, args$action_taken, args$result),
    "memory_record_tool_call" = client$reasoning$record_tool_call(
      args$step_id %||% "", args$tool_name,
      list(input = args$input %||% ""),
      args$output, args$status %||% "success",
      args$duration_ms),
    "memory_get_trace" = client$reasoning$get_trace_by_conversation(args$conversation_id),
    "memory_explain_decision" = client$reasoning$explain_step(args$step_id),
    stop(sprintf("Unknown memory tool: %s", name), call. = FALSE)
  )
}

mcp_dispatch_add_messages <- function(client, args) {
  conversation_id <- args$conversation_id
  msgs <- args$messages
  if (length(msgs) == 1) {
    m <- msgs[[1]]
    return(list(client$short_term$add_message(
      conversation_id, m$role, m$content, m$metadata)))
  }
  client$short_term$bulk_add_messages(conversation_id, msgs)
}
