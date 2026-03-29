FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.mod .
COPY ../../../clients/go /clients/go
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o forge .

FROM alpine:3.19
COPY --from=builder /app/forge /forge
EXPOSE 8003
CMD ["/forge"]
