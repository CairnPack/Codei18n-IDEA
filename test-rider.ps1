param([string]$RiderHome=$env:RIDER_HOME)
# Headless JetBrains Rider: the C# sample (examples/languages/Ledger.cs) through the no-PSI index.json fallback.
# Isolated config/system/plugins/log under build/integration-rider; needs the plugin built by build.ps1 first.
$ErrorActionPreference='Stop'
if (-not $RiderHome) {
 $installation=Get-ChildItem 'C:\Program Files\JetBrains' -Directory -Filter 'JetBrains Rider *' | Sort-Object Name -Descending | Select-Object -First 1
 if ($installation) {$RiderHome=$installation.FullName}
}
if (-not $RiderHome -or -not (Test-Path (Join-Path $RiderHome 'product-info.json'))) {throw 'Specify -RiderHome or RIDER_HOME.'}
if (-not (Test-Path "$PSScriptRoot/build/package/codei18n-idea")) {throw 'Run build.ps1 first.'}
$root=Join-Path $PSScriptRoot 'build/integration-rider'
New-Item -ItemType Directory -Force "$root/config","$root/system","$root/log","$root/plugins/harness/lib","$root/screenshots/rider" | Out-Null
foreach($old in @("$root/result.txt","$root/log/idea.log","$root/screenshots/rider/csharp.png")){if(Test-Path -LiteralPath $old){Remove-Item -LiteralPath $old -Force}}
# The sample project is refreshed on every run (the test rewrites .codei18n/mappings.json and restores it).
foreach($fresh in @("$root/project","$root/harness-classes")){if(Test-Path -LiteralPath $fresh){Remove-Item -LiteralPath $fresh -Recurse -Force}}
New-Item -ItemType Directory -Force "$root/harness-classes/META-INF" | Out-Null
Copy-Item "$PSScriptRoot/examples/languages" "$root/project" -Recurse -Force
if(Test-Path -LiteralPath "$root/project/.codei18n/cache"){Remove-Item -LiteralPath "$root/project/.codei18n/cache" -Recurse -Force}
# Replace, not merge: a jar left over from another plugin version would load twice.
if(Test-Path -LiteralPath "$root/plugins/codei18n-idea"){Remove-Item -LiteralPath "$root/plugins/codei18n-idea" -Recurse -Force}
Copy-Item "$PSScriptRoot/build/package/codei18n-idea" "$root/plugins" -Recurse -Force
# The harness needs only the platform and the main plugin, so it compiles against Rider's own jars.
$sdkClasspath=(Get-ChildItem "$RiderHome/lib" -Filter '*.jar' | ForEach-Object FullName) -join ';'
$compileArgs=@('--release','21','-encoding','UTF-8','-classpath',"$PSScriptRoot/build/classes;$sdkClasspath",'-d',"$root/harness-classes","$PSScriptRoot/integration/rider/RiderStarter.java","$PSScriptRoot/integration/LanguageSamplesTest.java")
$compileArgs | ForEach-Object {'"'+$_.Replace('\','/')+'"'} | Set-Content "$root/compiler.args" -Encoding utf8
& "$RiderHome/jbr/bin/java.exe" -m jdk.compiler/com.sun.tools.javac.Main "@$root/compiler.args"
if($LASTEXITCODE -ne 0){throw 'Harness compilation failed'}
Copy-Item "$PSScriptRoot/integration/rider/plugin.xml" "$root/harness-classes/META-INF/plugin.xml" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$harnessJar=Join-Path $root 'plugins/harness/lib/harness.jar'
if(Test-Path -LiteralPath $harnessJar){Remove-Item -LiteralPath $harnessJar -Force}
[IO.Compression.ZipFile]::CreateFromDirectory("$root/harness-classes",$harnessJar)
$portable=$root.Replace('\','/')
@"
idea.config.path=$portable/config
idea.system.path=$portable/system
idea.plugins.path=$portable/plugins
idea.log.path=$portable/log
idea.is.internal=true
idea.first.ide.session=false
idea.initially.ask.config=false
idea.privacy.policy.text=<!--999.999-->
codei18n.rider.project=$portable/project
codei18n.rider.result=$portable/result.txt
codei18n.rider.screenshot=$portable/screenshots/rider/csharp.png
"@ | Set-Content "$root/idea.properties" -Encoding ascii
$launch=(Get-Content "$RiderHome/product-info.json" -Raw | ConvertFrom-Json).launch[0]
$classpath=($launch.bootClassPathJarNames | ForEach-Object {Join-Path "$RiderHome/lib" $_}) -join ';'
$jvmArgs=@($launch.additionalJvmArguments | ForEach-Object { $_.Replace('$IDE_HOME', $RiderHome).Replace('%IDE_HOME%', $RiderHome) })
& "$RiderHome/jbr/bin/java.exe" @jvmArgs '-Xmx2048m' '-Djava.awt.headless=true' '-Didea.is.headless=true' '-Didea.trust.all.projects=true' "-Didea.properties.file=$root/idea.properties" -classpath $classpath com.intellij.idea.Main codei18n-rider-smoke 2>&1 | Tee-Object "$root/stdout.log"
if($LASTEXITCODE -ne 0){throw "Rider smoke failed ($LASTEXITCODE): see $root/stdout.log"}
Get-Content "$root/result.txt"
