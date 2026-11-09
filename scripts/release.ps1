<#
Deskport のリリースを作る: ビルド → 更新情報の署名 → (-Publish をつけたとき)GitHub Releases に公開。

使い方:
  .\scripts\release.ps1            ビルドと署名だけ行い、dist\ に出力する(公開しない)
  .\scripts\release.ps1 -Publish   上に加えて、GitHub Releases にタグ v<版> で公開する

前提:
  - 版は、host\Cargo.toml の version と、app\build.gradle.kts の versionName をそろえておく。
    端末に更新を出すときは、app\build.gradle.kts の versionCode を必ず増やす(増えていないと、更新とみなされない)。
  - 署名鍵は、リポジトリの隣の deskport-keys(環境変数 DESKPORT_KEYS で変えられる)にある(release.key = 更新情報の署名、deskport-release.jks + keystore.properties = apk の署名)。
    どちらも、なくすと更新を出せなくなる。リポジトリには入れない。
  - 公開には gh(GitHub CLI)のログインが要る。
#>
param([switch]$Publish)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$repo = 'ganondorofu/deskport'
$env:Path = "$env:USERPROFILE\.cargo\bin;$env:Path"
# libopus(音声の圧縮)のビルドに、新しい cmake が要る。PATH の cmake が古い(3.14 など)と、Visual Studio 2022 を認識できない。Visual Studio 付属のものを優先する
$vsCmake = Get-ChildItem "C:\Program Files\Microsoft Visual Studio\2022\*\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($vsCmake) { $env:Path = "$($vsCmake.DirectoryName);$env:Path" }
if (-not $env:DESKPORT_KEYS) { $env:DESKPORT_KEYS = Join-Path (Split-Path $root -Parent) 'deskport-keys' }
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'

# 版の確認
$hostVer = [regex]::Match((Get-Content "$root\host\Cargo.toml" -Raw), '(?m)^version = "([^"]+)"').Groups[1].Value
$gradle = Get-Content "$root\app\build.gradle.kts" -Raw
$code = [regex]::Match($gradle, 'versionCode = (\d+)').Groups[1].Value
$name = [regex]::Match($gradle, 'versionName = "([^"]+)"').Groups[1].Value
if ($hostVer -ne $name) { throw "ホストの版($hostVer)と、アプリの版($name)が違います。そろえてください" }
if (-not (Test-Path "$env:DESKPORT_KEYS\keystore.properties")) { throw 'apk の署名鍵(keystore.properties)がありません' }
if (-not (Test-Path "$env:DESKPORT_KEYS\release.key")) { throw '更新情報の署名鍵(release.key)がありません' }
Write-Host "版 $name(versionCode $code)をビルドします"

# ビルド
# 仮想カメラの DLL を先にビルドする(ホストに埋め込むため)
Push-Location "$root\vcam"; cargo build --release; if ($LASTEXITCODE) { throw '仮想カメラのビルドに失敗' }; Pop-Location
Push-Location "$root\host"; cargo build --release; if ($LASTEXITCODE) { throw 'ホストのビルドに失敗' }; Pop-Location
Push-Location $root; .\gradlew.bat assembleRelease -q; if ($LASTEXITCODE) { throw 'アプリのビルドに失敗' }; Pop-Location
$exe = "$root\host\target\release\deskport-host.exe"
$apk = "$root\app\build\outputs\apk\release\app-release.apk"

# 更新情報の作成と署名(窓なしの exe なので、終わるのを待つ)
$dist = "$root\dist"
if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
Start-Process -FilePath $exe -ArgumentList @('release', "`"$exe`"", "`"$apk`"", $code, $name, "`"$dist`"") -Wait -NoNewWindow
if (-not (Test-Path "$dist\manifest.sig")) { throw '更新情報の署名に失敗しました' }
Get-ChildItem $dist | Select-Object Name, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 2) } } | Format-Table -AutoSize

if (-not $Publish) { Write-Host '公開はしていません(-Publish をつけると、GitHub Releases に公開します)'; return }

# 公開
$tag = "v$name"
gh release create $tag "$dist\manifest.json" "$dist\manifest.sig" "$dist\deskport-host.exe" "$dist\deskport.apk" --repo $repo --title "Deskport $tag" --notes "Deskport $tag"
if ($LASTEXITCODE) { throw '公開に失敗しました' }
Write-Host "公開しました: https://github.com/$repo/releases/tag/$tag"
