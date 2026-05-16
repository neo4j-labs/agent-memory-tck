#' @title AuthClient — API key & OAuth refresh management
#' @description Hosted service only.
#' @export
AuthClient <- R6::R6Class("AuthClient",
  public = list(
    initialize = function(transport) {
      private$transport <- transport
    },

    list_api_keys = function(workspace_id) {
      result <- private$transport$request("list_api_keys", list(
        workspace_id = workspace_id
      ))
      if (is.null(result)) list() else result
    },

    create_api_key = function(label, scopes, workspace_id) {
      private$transport$request("create_api_key", list(
        label = label,
        scopes = scopes,
        workspace_id = workspace_id
      ))
    },

    revoke_api_key = function(key_id) {
      private$transport$request("revoke_api_key", list(
        key_id = as.character(key_id)
      ))
      invisible(NULL)
    },

    reveal_api_key = function(key_id, workspace_id) {
      private$transport$request("reveal_api_key", list(
        key_id = as.character(key_id),
        workspace_id = workspace_id
      ))
    },

    refresh_access_token = function(refresh_token) {
      private$transport$request("refresh_access_token", list(
        refresh_token = refresh_token
      ))
    }
  ),

  private = list(transport = NULL)
)
