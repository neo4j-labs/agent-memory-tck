#' @title Long-Term Memory Client
#' @description Manages entities, preferences, facts, and relationships.
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

    search_entities = function(query, limit = 10L) {
      result <- private$transport$request("search_entities", list(
        query = query,
        limit = as.integer(limit)
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
      result <- private$transport$request("get_entity_by_name", list(
        name = name
      ))
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
    }
  ),

  private = list(
    transport = NULL
  )
)
