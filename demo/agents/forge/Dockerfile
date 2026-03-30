FROM golang:1.22-alpine AS builder

WORKDIR /build

# Copy the Go client library first (needed via replace directive)
COPY clients/go/ /build/clients/go/

# Copy forge source
COPY demo/agents/forge/go.mod /build/forge/go.mod
COPY demo/agents/forge/main.go /build/forge/main.go

WORKDIR /build/forge

# Fix replace directive for Docker paths
RUN sed -i 's|../../../clients/go|/build/clients/go|' go.mod
RUN go mod tidy
RUN CGO_ENABLED=0 go build -o /forge .

FROM alpine:3.19
COPY --from=builder /forge /forge
EXPOSE 8003
CMD ["/forge"]
