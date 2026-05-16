#' @title Long-Term Memory Client
#' @description Manages entities, preferences, facts, relationships, and the
#'   hosted graph operations.
#' @export
LongTermMemory <- R6::R6Class("LongTermMemory",
  public = list(
    initialize = function(transport) {
      private$transport <- transport
    },

    add_entity = function(name, entity_type, description = NULL) {
      result <- private$transport$request("add_entity", list(
        name = name,
        entity_type = entity_type,
        type = entity_type,
        description = description
      ))
      parse_entity(result)
    },

    add_preference = function(category, preference, context = NULL) {
      result <- private$transport$request("add_preference", list(
        category = category,
        preference = preference,
        context = context
      ))
      parse_preference(result)
    },

    add_fact = function(subject, predicate, obj) {
      result <- private$transport$request("add_fact", list(
        subject = subject,
        predicate = predicate,
        obj = obj
      ))
      parse_fact(result)
    },

    search_entities = function(query, limit = 10L, type = NULL) {
      result <- private$transport$request("search_entities", list(
        query = query,
        limit = as.integer(limit),
        type = type
      ))
      if (is.null(result)) return(list())
      lapply(result, parse_entity)
    },

    search_preferences = function(query, category = NULL, limit = 10L) {
      result <- private$transport$request("search_preferences", list(
        query = query,
        category = category,
        limit = as.integer(limit)
      ))
      if (is.null(result)) return(list())
      lapply(result, parse_preference)
    },

    get_entity_by_name = function(name) {
      result <- private$transport$request("get_entity_by_name", list(name = name))
      if (is.null(result)) return(NULL)
      parse_entity(result)
    },

    get_related_entities = function(entity_id, relationship_type = NULL, depth = 1L) {
      result <- private$transport$request("get_related_entities", list(
        entity_id = as.character(entity_id),
        relationship_type = relationship_type,
        depth = as.integer(depth)
      ))
      if (is.null(result)) return(list())
      lapply(result, parse_entity)
    },

    add_relationship = function(source_id, target_id, relationship_type, properties = NULL) {
      result <- private$transport$request("add_relationship", list(
        source_id = as.character(source_id),
        target_id = as.character(target_id),
        relationship_type = relationship_type,
        properties = properties
      ))
      parse_relationship(result)
    },

    merge_duplicate_entities = function(source_id, target_id, canonical_name = NULL) {
      result <- private$transport$request("merge_duplicate_entities", list(
        source_id = as.character(source_id),
        target_id = as.character(target_id),
        canonical_name = canonical_name
      ))
      parse_entity(result)
    },

    # ---- Volume 5 / hosted-native ----------------------------------------

    list_entities = function(type = NULL, limit = NULL) {
      result <- private$transport$request("list_entities", list(
        type = type,
        limit = if (!is.null(limit)) as.integer(limit) else NULL
      ))
      if (is.null(result)) return(list())
      lapply(result, parse_entity)
    },

    get_entity = function(entity_id) {
      result <- private$transport$request("get_entity", list(
        entity_id = as.character(entity_id)
      ))
      parse_entity(result)
    },

    # Update an existing entity's name and/or description.
    #
    # The hosted PUT /v1/entities/{id} returns {"status": "updated"}
    # rather than the full entity, so when the response lacks an `id` we
    # follow up with a GET to keep the contract — "update returns the
    # updated entity". Bridge transports return the entity directly.
    update_entity = function(entity_id, name = NULL, description = NULL) {
      result <- private$transport$request("update_entity", list(
        entity_id = as.character(entity_id),
        name = name,
        description = description
      ))
      if (!is.null(result) && !is.null(result$id) && nchar(result$id) > 0) {
        return(parse_entity(result))
      }
      self$get_entity(entity_id)
    },

    delete_entity = function(entity_id) {
      private$transport$request("delete_entity", list(
        entity_id = as.character(entity_id)
      ))
      invisible(NULL)
    },

    set_entity_feedback = function(entity_id, user_score, confirmed) {
      result <- private$transport$request("set_entity_feedback", list(
        entity_id = as.character(entity_id),
        user_score = as.numeric(user_score),
        confirmed = isTRUE(confirmed)
      ))
      list(id = result$id, updated = isTRUE(result$updated))
    },

    get_entity_history = function(entity_id) {
      result <- private$transport$request("get_entity_history", list(
        entity_id = as.character(entity_id)
      ))
      parse_entity_history(result)
    },

    merge_entities = function(source_id, target_id) {
      result <- private$transport$request("merge_entities", list(
        source_id = as.character(source_id),
        target_id = as.character(target_id)
      ))
      list(
        source_id = result$source_id,
        target_id = result$target_id,
        status = result$status
      )
    },

    get_entity_graph = function() {
      result <- private$transport$request("get_entity_graph")
      list(
        nodes = if (is.null(result$nodes)) list() else result$nodes,
        edges = if (is.null(result$edges)) list() else result$edges
      )
    }
  ),

  private = list(transport = NULL)
)
