FROM rocker/r-ver:4.4.0

RUN apt-get update && apt-get install -y --no-install-recommends \
    libcurl4-openssl-dev \
    libssl-dev \
    libsodium-dev \
    && rm -rf /var/lib/apt/lists/*

RUN R -e "install.packages(c('plumber', 'jsonlite', 'httr2'), \
    repos='https://cloud.r-project.org/')"

WORKDIR /app
COPY demo/agents/rune/ /app/

EXPOSE 8006

CMD ["Rscript", "-e", "pr <- plumber::plumb('/app/plumber.R'); pr$run(host='0.0.0.0', port=as.integer(Sys.getenv('PORT', '8006')))"]
