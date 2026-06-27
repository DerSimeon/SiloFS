param(
    [switch]$Rerun,
    [switch]$SkipGo
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$lockParent = Join-Path $root "build\locks"
$lockDir = Join-Path $lockParent "production-verification.lock"
$gradlew = Join-Path $root "gradlew.bat"

New-Item -ItemType Directory -Force -Path $lockParent | Out-Null

$acquired = $false
while (-not $acquired) {
    try {
        New-Item -ItemType Directory -Path $lockDir -ErrorAction Stop | Out-Null
        $acquired = $true
    } catch {
        Write-Host "Waiting for production verification lock: $lockDir"
        Start-Sleep -Seconds 2
    }
}

try {
    $gradleArgs = @(
        ":metadata:test",
        ":blob:test",
        ":server:test",
        ":integration-test:test",
        ":compatibility-test:test",
        "dockerBackedVerification",
        "productionFocusedVerification",
        "ktlintKotlinScriptCheck",
        "--no-parallel",
        "--max-workers=1",
        "-x",
        "detekt"
    )
    if ($Rerun) {
        $gradleArgs += "--rerun-tasks"
    }

    & $gradlew @gradleArgs
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    if (-not $SkipGo) {
        $cliDir = Join-Path $root "cli"
        docker run --rm `
            -v "${cliDir}:/src" `
            -w /src `
            golang:1.23.5-bookworm `
            sh -c "go test ./... && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags '-s -w' -o /tmp/silofs . && /tmp/silofs version"
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }

    git -C $root diff --check
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    if ($acquired -and (Test-Path $lockDir)) {
        Remove-Item -LiteralPath $lockDir -Force
    }
}
