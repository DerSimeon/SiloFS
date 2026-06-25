FROM golang:1.23.5-bookworm

WORKDIR /compat
COPY go.mod go.sum compat.go /compat/
RUN go mod download
ENTRYPOINT ["go", "run", "-mod=readonly", "/compat/compat.go"]
