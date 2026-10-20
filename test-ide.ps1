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
# C5 language samples: a nested CodeI18n root, refreshed on every run (the test rewrites sources and .codei18n).
foreach($fresh in @("$root/project/languages","$root/branch-switch")){if(Test-Path -LiteralPath $fresh){Remove-Item -LiteralPath $fresh -Recurse -Force}}
Copy-Item "$PSScriptRoot/examples/languages" "$root/project/languages" -Recurse -Force
if(Test-Path -LiteralPath "$root/project/languages/.codei18n/cache"){Remove-Item -LiteralPath "$root/project/languages/.codei18n/cache" -Recurse -Force}
Copy-Item "$PSScriptRoot/integration/fixtures/branch-switch" "$root/branch-switch" -Recurse -Force
# Rider solution layout: .git and .codei18n at the repository root, the solution in src/backend.
$solutionLayout="$root/solution-layout"
if(Test-Path -LiteralPath $solutionLayout){Remove-Item -LiteralPath $solutionLayout -Recurse -Force}
New-Item -ItemType Directory -Force "$solutionLayout/.git","$solutionLayout/src/backend" | Out-Null
Copy-Item "$PSScriptRoot/examples/demo/.codei18n" $solutionLayout -Recurse -Force
Copy-Item "$PSScriptRoot/examples/demo/Demo.java" "$solutionLayout/src/backend" -Force
# Replace, not merge: a jar left over from another plugin version would load twice.
if(Test-Path -LiteralPath "$root/plugins/codei18n-idea"){Remove-Item -LiteralPath "$root/plugins/codei18n-idea" -Recurse -Force}
Copy-Item "$PSScriptRoot/build/package/codei18n-idea" "$root/plugins" -Recurse -Force
$sdkClasspath=(Get-ChildItem "$IdeaHome/lib" -Filter '*.jar' | ForEach-Object FullName) -join ';'
$compileArgs=@('--release','21','-encoding','UTF-8','-classpath',"$PSScriptRoot/build/classes;$sdkClasspath",'-d',"$root/harness-classes","$PSScriptRoot/integration/SmokeStarter.java","$PSScriptRoot/integration/HighlightingTest.java","$PSScriptRoot/integration/DisplayModeTest.java","$PSScriptRoot/integration/LanguageSamplesTest.java","$PSScriptRoot/integration/SolutionLayoutTest.java")
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
codei18n.smoke.languages=$portable/project/languages
codei18n.smoke.branch=$portable/branch-switch
codei18n.smoke.screenshots=$portable/screenshots
codei18n.smoke.solution=$portable/solution-layout
"@ | Set-Content "$root/idea.properties" -Encoding ascii
$launch=(Get-Content "$IdeaHome/product-info.json" -Raw | ConvertFrom-Json).launch[0]
$classpath=($launch.bootClassPathJarNames | ForEach-Object {Join-Path "$IdeaHome/lib" $_}) -join ';'
$jvmArgs=@($launch.additionalJvmArguments | ForEach-Object { $_.Replace('$IDE_HOME', $IdeaHome).Replace('%IDE_HOME%', $IdeaHome) })
& "$IdeaHome/jbr/bin/java.exe" @jvmArgs '-Xmx1536m' '-Djava.awt.headless=true' '-Didea.is.headless=true' '-Didea.trust.all.projects=true' "-Didea.properties.file=$root/idea.properties" -classpath $classpath com.intellij.idea.Main codei18n-smoke 2>&1 | Tee-Object "$root/stdout.log"
if($LASTEXITCODE -ne 0){throw "IDEA smoke failed ($LASTEXITCODE): see $root/stdout.log"}
Get-Content "$root/result.txt"

