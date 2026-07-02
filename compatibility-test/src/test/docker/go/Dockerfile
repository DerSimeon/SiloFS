FROM golang:1.25-bookworm

WORKDIR /compat
COPY go.mod go.sum compat.go /compat/
RUN /usr/local/go/bin/go mod download
ENTRYPOINT ["/usr/local/go/bin/go", "run", "-mod=readonly", "/compat/compat.go"]
