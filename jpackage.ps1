# Builds a standalone Windows executable (app-image) for jms-spy using jpackage.
# Requires JDK 14+ (jpackage is bundled with the JDK). Run from the repo root:
#   .\jpackage.ps1
#
# Output:
#   target\dist\JmsSpy\JmsSpy.exe         (GUI only, no console)
#   target\dist\JmsSpy\JmsSpyConsole.exe   (GUI + console window, same app image)
# Both are self-contained and bundle their own JRE.

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

Write-Host "Building application jar..."
& mvn -q clean package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

Write-Host "Assembling jpackage input directory..."
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

Write-Host "Running jpackage..."
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
    --vendor "eljaiek" `
    --description "Demo project for Spring Boot and JavaFX" `
    --add-launcher "${AppName}_with_Console=$ConsoleLauncherProps"
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

Write-Host "Done:"
Write-Host "  $DestDir\$AppName\$AppName.exe          (no console)"
Write-Host "  $DestDir\$AppName\${AppName}_with_Console.exe  (with console)"
