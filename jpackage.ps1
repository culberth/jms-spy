# Builds a standalone Windows executable (app-image) for jms-spy using jpackage.
# Requires a full JDK 14+ (not just a JRE) on PATH/JAVA_HOME — jlink needs the jmods
# directory, which only ships with a full JDK. Run from the repo root:
#   .\jpackage.ps1
#
# Output:
#   target\dist\JmsSpy\JmsSpy.exe               (GUI only, no console)
#   target\dist\JmsSpy\JmsSpy_with_Console.exe  (GUI + console window, same app image)
# Both are fully self-contained: they bundle a custom, minimal JRE built by jlink
# (only the JDK modules this app actually uses, via jdeps analysis), so they run on a
# machine with no Java installed at all.

$ErrorActionPreference = "Stop"

$AppName = "JmsSpy"
$MainClass = "com.example.jfx.spring.jms.MainApplication"

$PomContent = Get-Content pom.xml -Raw
if ($PomContent -notmatch '(?s)</parent>.*?<version>([^<]+)</version>') {
    throw "Could not determine project version from pom.xml"
}
$ProjectVersion = $Matches[1]
$AppVersion = $ProjectVersion -replace '-SNAPSHOT$', ''
$JarName = "jms-spy-$ProjectVersion.jar"

if ($PomContent -notmatch '<java\.version>([^<]+)</java\.version>') {
    throw "Could not determine java.version from pom.xml"
}
$JavaVersion = $Matches[1]

Write-Host "1. Building application jar..." -Foreground Cyan
& mvn -q clean package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

Write-Host "2. Assembling jpackage input directory..." -Foreground Cyan
$InputDir = "target\jpackage-input"
if (Test-Path $InputDir) { Remove-Item -Recurse -Force $InputDir }
New-Item -ItemType Directory -Force -Path $InputDir | Out-Null

& mvn -q dependency:copy-dependencies "-DoutputDirectory=$InputDir" -DincludeScope=runtime
if ($LASTEXITCODE -ne 0) { throw "Failed to copy runtime dependencies" }

# Lombok is compile-time only (annotation processing); it's harmless but unused at
# runtime, so drop it from the bundled app rather than fight cmd.exe's argument
# quoting for -DexcludeGroupIds when invoked through mvnw.cmd from PowerShell.
Remove-Item "$InputDir\lombok-*.jar" -ErrorAction SilentlyContinue

# The Spring Boot plugin renames the plain (thin) jar to *.jar.original and replaces
# the original name with the fat/executable jar. jpackage needs the thin jar plus the
# dependency jars copied above, flattened into one directory, on its plain classpath.
Copy-Item "target\$JarName.original" "$InputDir\$JarName"

$ConsoleLauncherProps = "target\jpackage-console-launcher.properties"
"win-console=true" | Set-Content $ConsoleLauncherProps

Write-Host "3. Locating JDK modules (jmods) for jlink..." -Foreground Cyan
$JavaHome = $env:JAVA_HOME
if (-not $JavaHome) {
    $JavaExe = (Get-Command java -ErrorAction Stop).Source
    $JavaHome = Split-Path (Split-Path $JavaExe -Parent) -Parent
}
$JmodsDir = Join-Path $JavaHome "jmods"
if (-not (Test-Path $JmodsDir)) {
    throw "No jmods directory under '$JavaHome' - jlink requires a full JDK, not a JRE"
}

Write-Host "4. Determining required JDK modules via jdeps..." -Foreground Cyan
$JDepsOutput = & jdeps --multi-release $JavaVersion --ignore-missing-deps --print-module-deps `
    "--class-path=$InputDir\*" "$InputDir\$JarName" 2>&1
if ($LASTEXITCODE -ne 0) { throw "jdeps failed:`n$JDepsOutput" }
$Modules = ($JDepsOutput | Select-Object -Last 1).Trim()
# jdeps only sees modules referenced directly from bytecode; jdk.crypto.ec provides EC
# ciphers/TLS support that's normally pulled in via SPI/reflection at runtime (e.g. TLS
# to the Artemis broker), so jdeps won't detect it - add it unconditionally.
if ($Modules -notmatch '\bjdk\.crypto\.ec\b') { $Modules = "$Modules,jdk.crypto.ec" }
Write-Host "  Modules: $Modules" -Foreground Green

Write-Host "5. Building custom runtime image with jlink..." -Foreground Cyan
$RuntimeDir = "target\runtime"
if (Test-Path $RuntimeDir) { Remove-Item -Recurse -Force $RuntimeDir }
& jlink `
    --module-path $JmodsDir `
    --add-modules $Modules `
    --strip-debug `
    --no-header-files `
    --no-man-pages `
    --compress=zip-6 `
    --output $RuntimeDir
if ($LASTEXITCODE -ne 0) { throw "jlink failed" }

Write-Host "6. Running jpackage..." -Foreground Cyan
$DestDir = "target\dist"
if (Test-Path $DestDir) { Remove-Item -Recurse -Force $DestDir }

& jpackage `
    --type app-image `
    --input $InputDir `
    --dest $DestDir `
    --name $AppName `
    --main-jar $JarName `
    --main-class $MainClass `
    --app-version $AppVersion `
    --vendor "Slobberknocker Productions" `
    --description "Demo project for Spring Boot and JavaFX" `
    --runtime-image $RuntimeDir `
    --add-launcher "${AppName}_with_Console=$ConsoleLauncherProps"
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

Write-Host "Done:" -Foreground Green
Write-Host "  $DestDir\$AppName\${AppName}.exe (no console)" -Foreground Green
Write-Host "  $DestDir\$AppName\${AppName}_with_Console.exe (with console)" -Foreground Green

Start-Sleep -Seconds 5
