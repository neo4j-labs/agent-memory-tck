source("memory_helpers.R")

run_regression <- function(entities, x_property, y_property) {
  x_vals <- as.numeric(sapply(entities, function(e) e$description))
  y_vals <- as.numeric(sapply(entities, function(e) e$name))

  if (length(x_vals) < 2 || all(is.na(x_vals)) || all(is.na(y_vals))) {
    return(list(
      error = "Insufficient numeric data for regression",
      n = length(x_vals)
    ))
  }

  valid <- !is.na(x_vals) & !is.na(y_vals)
  x_vals <- x_vals[valid]
  y_vals <- y_vals[valid]

  if (length(x_vals) < 2) {
    return(list(error = "Fewer than 2 valid data points", n = length(x_vals)))
  }

  model <- lm(y_vals ~ x_vals)
  s <- summary(model)

  list(
    tool = "run_regression",
    n = length(x_vals),
    coefficients = as.list(coef(model)),
    r_squared = s$r.squared,
    adj_r_squared = s$adj.r.squared,
    f_statistic = if (!is.null(s$fstatistic)) s$fstatistic[[1]] else NULL,
    p_value = if (!is.null(s$fstatistic)) {
      pf(s$fstatistic[[1]], s$fstatistic[[2]], s$fstatistic[[3]], lower.tail = FALSE)
    } else NULL
  )
}

run_correlation <- function(entities, property1, property2, method = "pearson") {
  vals1 <- as.numeric(sapply(entities, function(e) e[[property1]]))
  vals2 <- as.numeric(sapply(entities, function(e) e[[property2]]))

  valid <- !is.na(vals1) & !is.na(vals2)
  vals1 <- vals1[valid]
  vals2 <- vals2[valid]

  if (length(vals1) < 3) {
    return(list(error = "Fewer than 3 valid data points", n = length(vals1)))
  }

  test <- cor.test(vals1, vals2, method = method)

  list(
    tool = "run_correlation",
    method = method,
    n = length(vals1),
    correlation = test$estimate[[1]],
    p_value = test$p.value,
    conf_low = if (!is.null(test$conf.int)) test$conf.int[1] else NULL,
    conf_high = if (!is.null(test$conf.int)) test$conf.int[2] else NULL
  )
}

run_clustering <- function(entities, properties, k = 3L) {
  mat <- sapply(properties, function(prop) {
    as.numeric(sapply(entities, function(e) e[[prop]]))
  })

  if (!is.matrix(mat)) mat <- matrix(mat, ncol = length(properties))

  valid_rows <- complete.cases(mat)
  mat <- mat[valid_rows, , drop = FALSE]

  if (nrow(mat) < k) {
    return(list(error = sprintf("Only %d valid rows, need at least %d", nrow(mat), k)))
  }

  result <- kmeans(mat, centers = k, nstart = 10)

  list(
    tool = "run_clustering",
    k = k,
    n = nrow(mat),
    cluster_sizes = as.list(result$size),
    centers = lapply(seq_len(nrow(result$centers)), function(i) as.list(result$centers[i, ])),
    within_ss = result$tot.withinss,
    between_ss = result$betweenss
  )
}

run_summary <- function(entities, property) {
  vals <- as.numeric(sapply(entities, function(e) e[[property]]))
  vals <- vals[!is.na(vals)]

  if (length(vals) == 0) {
    return(list(error = "No numeric values found", property = property))
  }

  q <- quantile(vals, probs = c(0.25, 0.5, 0.75))

  list(
    tool = "run_summary",
    property = property,
    n = length(vals),
    mean = mean(vals),
    median = median(vals),
    sd = sd(vals),
    min = min(vals),
    max = max(vals),
    q1 = q[[1]],
    q2 = q[[2]],
    q3 = q[[3]]
  )
}

query_entities <- function(query, limit = 20L) {
  result <- memory_search_entities(query, limit)
  if (is.null(result)) return(list())
  result
}
