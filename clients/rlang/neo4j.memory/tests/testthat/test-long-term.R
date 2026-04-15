# --- LongTermMemory unit tests (using MockTransport) ---

test_that("add_entity sends correct parameters", {
  mock <- MockTransport$new()
  mock$set_response("add_entity", list(
    id = "e-1", name = "Alice", type = "PERSON", created_at = "t"
  ))
  lt <- LongTermMemory$new(mock)

  result <- lt$add_entity("Alice", "PERSON", description = "Engineer")

  expect_equal(result$id, "e-1")
  expect_equal(result$name, "Alice")
  call <- mock$last_call()
  expect_equal(call$params$name, "Alice")
  expect_equal(call$params$entity_type, "PERSON")
  expect_equal(call$params$description, "Engineer")
})

test_that("add_entity omits description when NULL", {
  mock <- MockTransport$new()
  mock$set_response("add_entity", list(id = "e-1", name = "x", type = "OBJECT", created_at = "t"))
  lt <- LongTermMemory$new(mock)

  lt$add_entity("x", "OBJECT")

  call <- mock$last_call()
  expect_false("description" %in% names(call$params))
})

test_that("add_preference sends correct parameters", {
  mock <- MockTransport$new()
  mock$set_response("add_preference", list(id = "p-1", category = "theme", preference = "dark"))
  lt <- LongTermMemory$new(mock)

  result <- lt$add_preference("theme", "dark", context = "for coding")

  expect_equal(result$category, "theme")
  call <- mock$last_call()
  expect_equal(call$params$context, "for coding")
})

test_that("add_fact sends subject, predicate, obj", {
  mock <- MockTransport$new()
  mock$set_response("add_fact", list(
    id = "f-1", subject = "Alice", predicate = "WORKS_AT", object = "Acme"
  ))
  lt <- LongTermMemory$new(mock)

  result <- lt$add_fact("Alice", "WORKS_AT", "Acme")

  expect_equal(result$subject, "Alice")
  expect_equal(result$predicate, "WORKS_AT")
  expect_equal(result$object, "Acme")
  call <- mock$last_call()
  expect_equal(call$params$obj, "Acme")
})

test_that("search_entities returns list of entities", {
  mock <- MockTransport$new()
  mock$set_response("search_entities", list(
    list(id = "e1", name = "Alice", type = "PERSON", created_at = "t"),
    list(id = "e2", name = "Bob", type = "PERSON", created_at = "t")
  ))
  lt <- LongTermMemory$new(mock)

  results <- lt$search_entities("people")

  expect_equal(length(results), 2)
  expect_equal(results[[1]]$name, "Alice")
  expect_equal(results[[2]]$name, "Bob")
})

test_that("search_entities returns empty list on NULL", {
  mock <- MockTransport$new()
  mock$set_response("search_entities", NULL)
  lt <- LongTermMemory$new(mock)

  expect_equal(lt$search_entities("nothing"), list())
})

test_that("search_preferences passes category and limit", {
  mock <- MockTransport$new()
  mock$set_response("search_preferences", list())
  lt <- LongTermMemory$new(mock)

  lt$search_preferences("dark", category = "theme", limit = 5L)

  call <- mock$last_call()
  expect_equal(call$params$query, "dark")
  expect_equal(call$params$category, "theme")
  expect_equal(call$params$limit, 5L)
})

test_that("get_entity_by_name returns entity when found", {
  mock <- MockTransport$new()
  mock$set_response("get_entity_by_name", list(
    id = "e1", name = "Alice", type = "PERSON", created_at = "t"
  ))
  lt <- LongTermMemory$new(mock)

  result <- lt$get_entity_by_name("Alice")

  expect_equal(result$name, "Alice")
})

test_that("get_entity_by_name returns NULL when not found", {
  mock <- MockTransport$new()
  mock$set_response("get_entity_by_name", NULL)
  lt <- LongTermMemory$new(mock)

  expect_null(lt$get_entity_by_name("Unknown"))
})

test_that("get_related_entities passes all parameters", {
  mock <- MockTransport$new()
  mock$set_response("get_related_entities", list())
  lt <- LongTermMemory$new(mock)

  lt$get_related_entities("e1", relationship_type = "WORKS_AT", depth = 2L)

  call <- mock$last_call()
  expect_equal(call$params$entity_id, "e1")
  expect_equal(call$params$relationship_type, "WORKS_AT")
  expect_equal(call$params$depth, 2L)
})

test_that("add_relationship sends all parameters", {
  mock <- MockTransport$new()
  mock$set_response("add_relationship", list(
    id = "r1", source_id = "e1", target_id = "e2", relationship_type = "KNOWS"
  ))
  lt <- LongTermMemory$new(mock)

  result <- lt$add_relationship("e1", "e2", "KNOWS", properties = list(since = "2020"))

  expect_equal(result$source_id, "e1")
  call <- mock$last_call()
  expect_equal(call$params$properties$since, "2020")
})

test_that("merge_duplicate_entities sends IDs and canonical name", {
  mock <- MockTransport$new()
  mock$set_response("merge_duplicate_entities", list(
    id = "e1", name = "Alice Smith", type = "PERSON", canonical_name = "Alice", created_at = "t"
  ))
  lt <- LongTermMemory$new(mock)

  result <- lt$merge_duplicate_entities("e1", "e2", canonical_name = "Alice")

  expect_equal(result$canonical_name, "Alice")
  call <- mock$last_call()
  expect_equal(call$params$source_id, "e1")
  expect_equal(call$params$target_id, "e2")
  expect_equal(call$params$canonical_name, "Alice")
})
