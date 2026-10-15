param([string]$IdeaHome=$env:IDEA_HOME, [string]$JdkHome)
$ErrorActionPreference='Stop'
if (-not $IdeaHome) {
 $installation=Get-ChildItem 'C:\Program Files\JetBrains' -Directory -Filter 'IntelliJ IDEA *' | Sort-Object Name -Descending | Select-Object -First 1
 if ($installation) {$IdeaHome=$installation.FullName}
}
if (-not $IdeaHome) {throw 'Specify -IdeaHome or IDEA_HOME.'}
if (-not $JdkHome) {$JdkHome=Join-Path $IdeaHome 'jbr'}
$root=Join-Path $PSScriptRoot 'build/integration'
New-Item -ItemType Directory -Force "$root/config","$root/system","$root/log","$root/plugins/harness/lib","$root/harness-classes/META-INF","$root/project" | Out-Null
foreach($old in @("$root/result.txt","$root/log/idea.log")){if(Test-Path -LiteralPath $old){Remove-Item -LiteralPath $old -Force}}
Copy-Item "$PSScriptRoot/examples/demo/*" "$root/project" -Recurse -Force
Copy-Item "$PSScriptRoot/examples/demo/.codei18n" "$root/project" -Recurse -Force
Copy-Item "$PSScriptRoot/build/package/codei18n-idea" "$root/plugins" -Recurse -Force
$sdkClasspath=(Get-ChildItem "$IdeaHome/lib" -Filter '*.jar' | ForEach-Object FullName) -join ';'
$compileArgs=@('--release','21','-encoding','UTF-8','-classpath',"$PSScriptRoot/build/classes;$sdkClasspath",'-d',"$root/harness-classes","$PSScriptRoot/integration/SmokeStarter.java","$PSScriptRoot/integration/HighlightingTest.java")
$compileArgs | ForEach-Object {'"'+$_.Replace('\','/')+'"'} | Set-Content "$root/compiler.args" -Encoding utf8
& "$JdkHome/bin/java.exe" -m jdk.compiler/com.sun.tools.javac.Main "@$root/compiler.args"
if($LASTEXITCODE -ne 0){throw 'Harness compilation failed'}
Copy-Item "$PSScriptRoot/integration/plugin.xml" "$root/harness-classes/META-INF/plugin.xml" -Force
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
codei18n.smoke.project=$portable/project
codei18n.smoke.result=$portable/result.txt
"@ | Set-Content "$root/idea.properties" -Encoding ascii
$launch=(Get-Content "$IdeaHome/product-info.json" -Raw | ConvertFrom-Json).launch[0]
$classpath=($launch.bootClassPathJarNames | ForEach-Object {Join-Path "$IdeaHome/lib" $_}) -join ';'
$jvmArgs=@($launch.additionalJvmArguments | ForEach-Object { $_.Replace('$IDE_HOME', $IdeaHome).Replace('%IDE_HOME%', $IdeaHome) })
& "$IdeaHome/jbr/bin/java.exe" @jvmArgs '-Xmx1536m' '-Djava.awt.headless=true' '-Didea.is.headless=true' '-Didea.trust.all.projects=true' "-Didea.properties.file=$root/idea.properties" -classpath $classpath com.intellij.idea.Main codei18n-smoke 2>&1 | Tee-Object "$root/stdout.log"
if($LASTEXITCODE -ne 0){throw "IDEA smoke failed ($LASTEXITCODE): see $root/stdout.log"}
Get-Content "$root/result.txt"

