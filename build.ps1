param(
    [string]$IdeaHome = $env:IDEA_HOME,
    [string]$JdkHome
)
$ErrorActionPreference = 'Stop'
if (-not $IdeaHome) {
    $installation = Get-ChildItem 'C:\Program Files\JetBrains' -Directory -Filter 'IntelliJ IDEA *' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($installation) { $IdeaHome = $installation.FullName }
}
if (-not $IdeaHome -or -not (Test-Path (Join-Path $IdeaHome 'lib'))) {
    throw 'Specify -IdeaHome or IDEA_HOME pointing to an IntelliJ IDEA 2026.2 installation.'
}
if (-not $JdkHome) { $JdkHome = Join-Path $IdeaHome 'jbr' }
$javac = Join-Path $JdkHome 'bin/javac.exe'
$java = Join-Path $JdkHome 'bin/java.exe'
$output = Join-Path $PSScriptRoot 'build'
$classes = Join-Path $output 'classes'
$testClasses = Join-Path $output 'test-classes'
$package = Join-Path $output 'package/codei18n-idea/lib'
$distribution = Join-Path $output 'distributions'
# Only remove known generated directories, after checking their resolved scope.
foreach ($directory in @($classes, $testClasses, (Join-Path $output 'package'))) {
    $resolved = [IO.Path]::GetFullPath($directory)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath($output) + [IO.Path]::DirectorySeparatorChar)) {
        throw "Generated path is outside build directory: $resolved"
    }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
New-Item -ItemType Directory -Force $classes, $testClasses, $package, $distribution | Out-Null
$sdk = Join-Path $IdeaHome 'lib/*'
$sources = @(Get-ChildItem (Join-Path $PSScriptRoot 'src/main/java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& $javac --release 21 -encoding UTF-8 -classpath $sdk -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Plugin compilation failed.' }
Copy-Item (Join-Path $PSScriptRoot 'src/main/resources/*') $classes -Recurse -Force
$tests = @(Get-ChildItem (Join-Path $PSScriptRoot 'src/test/java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& $javac --release 21 -encoding UTF-8 -classpath "$classes;$sdk" -d $testClasses @tests
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
& $java -classpath "$testClasses;$classes;$sdk" dev.codei18n.idea.MappingIndexTest
if ($LASTEXITCODE -ne 0) { throw 'Tests failed.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($classes, (Join-Path $package 'codei18n-idea-0.1.1.jar'))
$zip = Join-Path $distribution 'codei18n-idea-0.1.1.zip'
Compress-Archive -Path (Join-Path $output 'package/codei18n-idea') -DestinationPath $zip -Force
Write-Output "Plugin: $zip"
