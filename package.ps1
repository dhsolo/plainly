# 打包 Plainly。
#
#   powershell -ExecutionPolicy Bypass -File package.ps1              # 只出 app-image（目录）
#   powershell -ExecutionPolicy Bypass -File package.ps1 -Msi         # 再出 MSI 安装包
#   powershell -ExecutionPolicy Bypass -File package.ps1 -Msi -PerUser  # 装到当前用户，免 UAC
#   powershell -ExecutionPolicy Bypass -File package.ps1 -Msi -SkipBuild
#
# app-image 是一个可以直接双击运行的目录，不需要任何额外工具。
#
# MSI 需要 WiX Toolset 3.x —— jpackage 在 Windows 上就是调 candle.exe / light.exe
# 生成安装包的。脚本会按这个顺序找：PATH → build\wix。都没有就用 -FetchWix
# 去官方仓库取一份免安装的二进制包解到 build\wix（不动系统、不需要管理员）。
#
# 代码签名不在这里做：签名需要一张证书（Azure Trusted Signing 按月付费，
# 或别家的 EV 证书），拿不到证书就签不了。不签名的安装包会被 SmartScreen 拦下来，
# 用户要点「更多信息 → 仍要运行」。这一点无法靠代码绕过，只能如实说明。

param(
    [switch]$Msi,
    [switch]$PerUser,
    [switch]$SkipBuild,
    [switch]$FetchWix,
    [string]$Version
)

$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
if (-not $root) { $root = (Get-Location).Path }

$appName = 'Plainly'
$mainJar = 'plainly.jar'
$mainClass = 'com.plainly.app.Launcher'
$outDir = Join-Path $root 'dist'
$buildDir = Join-Path $root 'build'
$inputDir = Join-Path $buildDir 'package-input'
$iconFile = Join-Path $buildDir 'plainly.ico'
$wixDir = Join-Path $buildDir 'wix'
$wixUrl = 'https://github.com/wixtoolset/wix3/releases/download/wix3112rtm/wix311-binaries.zip'

# 升级用的 GUID。**永远不要改这一行。**
#
# MSI 靠它认出「这是同一个产品的新版本」。改掉之后，新版本不再升级旧版本，
# 而是和它并排装两份——两个开始菜单项、两个卸载入口，且谁也不知道自己在用哪个。
$upgradeUuid = 'caae7b65-db7c-44ca-aabb-98147b30dff5'

function Fail($message) {
    Write-Output ''
    Write-Output $message
    exit 1
}

# ---------------------------------------------------------------- 版本号
#
# 版本号只有一个来源：根 pom.xml。
#
# 原来这里写着 $version = '0.1.0'，和 pom 里那个各写各的。两处一旦不同步，
# 后果不是报错而是**升级悄悄没发生**：pom 改成 0.2.0、这里忘了改，
# 出来的 MSI 仍然自称 0.1.0，装到已有 0.1.0 的机器上，Windows 认为
# 「同一个版本」，于是既不升级也不提示——用户双击装完，用的还是旧的。

function Get-PomVersion {
    $pom = Join-Path $root 'pom.xml'
    if (-not (Test-Path $pom)) { Fail "没找到 $pom" }
    [xml]$xml = Get-Content $pom -Encoding UTF8
    # 根 <project><version>，不是 <parent> 或依赖里的那些
    $raw = $xml.project.version
    if (-not $raw) { Fail 'pom.xml 里没有根级 <version>' }
    # MSI 不认 -SNAPSHOT 这类后缀，去掉
    return ($raw -replace '-SNAPSHOT$', '')
}

<#
 .SYNOPSIS
 按 MSI 的规矩检查版本号。

 .DESCRIPTION
 MSI 的 ProductVersion 只比较前三段，且每段有上限（主 255、次 255、构建 65535），
 必须是纯数字。写成 1.0.0-rc1 或 1.0.0.4，jpackage 要么直接拒绝，
 要么第四段被无视——后者更糟：1.0.0.4 和 1.0.0.5 在 Windows 眼里是同一个版本，
 升级同样不会发生。
#>
function Assert-MsiVersion($v) {
    if ($v -notmatch '^\d+\.\d+\.\d+$') {
        Fail "版本号 '$v' 不符合 MSI 的要求。`nMSI 只认 主.次.构建 三段纯数字（如 0.2.0）；" +
             "`n-SNAPSHOT 已自动去掉，其余后缀（-rc1、+build 之类）请先从 pom.xml 里去掉。"
    }
    $parts = $v.Split('.')
    if ([int]$parts[0] -gt 255) { Fail "主版本 $($parts[0]) 超出 MSI 上限 255" }
    if ([int]$parts[1] -gt 255) { Fail "次版本 $($parts[1]) 超出 MSI 上限 255" }
    if ([int]$parts[2] -gt 65535) { Fail "构建号 $($parts[2]) 超出 MSI 上限 65535" }
}

if ($Version) {
    $version = $Version
    Write-Output "版本 $version（命令行指定，未采用 pom.xml 里的）"
} else {
    $version = Get-PomVersion
    Write-Output "版本 $version（取自 pom.xml）"
}
Assert-MsiVersion $version

# ---------------------------------------------------------------- 1. 构建

if (-not $SkipBuild) {
    Write-Output '[1/4] 构建…'
    $settings = Join-Path $root 'settings.xml'
    & mvn -s $settings -gs $settings -q package
    if ($LASTEXITCODE -ne 0) { Fail '构建失败，打包中止' }
} else {
    Write-Output '[1/4] 跳过构建'
}

$jar = Join-Path $root 'plainly-app\target\plainly.jar'
if (-not (Test-Path $jar)) { Fail "没找到 $jar，先跑一次构建（去掉 -SkipBuild）" }

# ---------------------------------------------------------------- 2. 素材

Write-Output '[2/4] 收集 jar 与图标…'

# jpackage 要求所有 jar 摊在同一个目录里
if (Test-Path $inputDir) { Remove-Item $inputDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $inputDir | Out-Null
Copy-Item $jar $inputDir
Copy-Item (Join-Path $root 'plainly-app\target\deps\*.jar') $inputDir

# 图标从代码里那份生成，不是仓库里另放一张：
# 另放一张就意味着改了 Icons 之后安装包上的图标不会跟着变，而且没有任何提示
$deps = Join-Path $root 'plainly-app\target\deps\*'
& java "-Dfile.encoding=UTF-8" -cp "$jar;$deps" (Join-Path $root 'tools\MakeAppIcon.java') $iconFile
if (-not (Test-Path $iconFile)) { Fail '图标生成失败' }

if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# ---------------------------------------------------------------- 3. app-image

# JavaFX 是从 classpath 加载的（非模块化），所以不 jlink 裁模块路径；
# 但运行时镜像里要带哪些 JDK 模块得自己说清楚——jpackage 靠 jdeps 静态分析，
# 而下面这几个都是**反射用到、静态分析看不见**的，漏掉不会在打包时报错，
# 只会在用户机器上以一种看不出原因的方式失败：
#   jdk.crypto.ec        少了它，连 MySQL / PostgreSQL 的 TLS 握手会挑不出算法套件
#   jdk.charsets         少了它，Charset.forName("GBK") 抛异常——导入导出选 GBK 就炸
#   jdk.localedata       少了它，中文环境下的日期与数字格式退回英文
#   java.sql.rowset      部分 JDBC 驱动会用到
#   jdk.unsupported      sun.misc.Unsafe，若干库仍在用
#   java.security.sasl   认证走 SASL 的那几家都要：MySQL 8 的 caching_sha2_password、
#                        PostgreSQL 的 SCRAM-SHA-256、MongoDB 的 SCRAM。少了它，
#                        开发时跑 run.cmd 一切正常（那是完整 JDK），装完的版本一登录就
#                        NoClassDefFoundError——而报错里不会提「模块」两个字
#   jdk.net              Oracle 驱动用 jdk.net.ExtendedSocketOptions 调 TCP keepalive
#
# 这份清单是用 jdeps 逐个 jar 核出来的，别凭印象增删：
#   jdeps --multi-release 17 --ignore-missing-deps --print-module-deps ^
#         --class-path "<deps 里所有 jar>" <某个 jar>
# 加新依赖（尤其是新数据库驱动）之后要重跑一遍，见 README「怎么发下一个版本」。
$modules = @(
    'java.base', 'java.desktop', 'java.logging', 'java.management', 'java.naming',
    'java.net.http', 'java.prefs', 'java.scripting', 'java.security.jgss',
    'java.security.sasl',
    'java.sql', 'java.sql.rowset', 'java.transaction.xa', 'java.xml',
    'jdk.charsets', 'jdk.crypto.cryptoki', 'jdk.crypto.ec',
    'jdk.localedata', 'jdk.net', 'jdk.unsupported', 'jdk.zipfs'
) -join ','

$common = @(
    '--name', $appName,
    '--app-version', $version,
    '--input', $inputDir,
    '--main-jar', $mainJar,
    '--main-class', $mainClass,
    '--icon', $iconFile,
    '--dest', $outDir,
    # 发布者。填人不填产品名——「名称」那一列已经是 Plainly 了，
    # 两列写同一个词等于没提供信息。这一栏的作用是让人知道东西是谁发的。
    #
    # 注意它只影响装完之后「程序和功能」里那一列：安装时 UAC 弹窗上显示的
    # 始终是「未知发布者」，因为这个包没有代码签名，跟这里填什么无关。
    '--vendor', 'dhsolo',
    '--copyright', 'Copyright 2026 戴虎 (dhsolo)',
    '--description', '跨数据库管理工具',
    '--add-modules', $modules,
    '--java-options', '-Dfile.encoding=UTF-8',
    '--java-options', '-Dsun.stdout.encoding=UTF-8',
    '--java-options', '-Dsun.stderr.encoding=UTF-8'
)

Write-Output '[3/4] jpackage app-image…'
& jpackage @common --type app-image
if ($LASTEXITCODE -ne 0) { Fail 'app-image 生成失败' }
Write-Output "  已生成：$outDir\$appName\$appName.exe"

if (-not $Msi) {
    Write-Output '[4/4] 未指定 -Msi，到此为止'
    exit 0
}

# ---------------------------------------------------------------- 4. MSI

Write-Output '[4/4] 准备 WiX…'

function Find-Wix {
    # 一、PATH 上已经有
    $onPath = Get-Command candle.exe -ErrorAction SilentlyContinue
    if ($onPath) { return Split-Path -Parent $onPath.Source }
    # 二、本仓库 build\wix 下解压过一份
    if (Test-Path (Join-Path $wixDir 'candle.exe')) { return $wixDir }
    return $null
}

$wixBin = Find-Wix

if (-not $wixBin -and $FetchWix) {
    Write-Output "  本机没有 WiX，去取一份免安装的二进制包…"
    Write-Output "  $wixUrl"
    $zip = Join-Path $buildDir 'wix311-binaries.zip'
    New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
    try {
        Invoke-WebRequest -Uri $wixUrl -OutFile $zip -TimeoutSec 300 -UseBasicParsing
    } catch {
        Fail "下载失败：$($_.Exception.Message)`n手动下载后解压到 $wixDir 再跑一次。"
    }
    if (Test-Path $wixDir) { Remove-Item $wixDir -Recurse -Force }
    Expand-Archive -Path $zip -DestinationPath $wixDir -Force
    Remove-Item $zip -Force
    $wixBin = Find-Wix
}

if (-not $wixBin) {
    Fail @"
没找到 WiX Toolset 3.x。jpackage 在 Windows 上靠它生成 MSI（candle.exe / light.exe）。

三选一：
  1. 让脚本自己取一份免安装的（不动系统、不需要管理员）：
       powershell -ExecutionPolicy Bypass -File package.ps1 -Msi -FetchWix
  2. 手动下载 wix311-binaries.zip 解压到：
       $wixDir
       $wixUrl
  3. 装官方安装包，并把它的 bin 目录加进 PATH。

app-image 已经生成，就在 $outDir\$appName，那个目录可以直接分发、直接双击运行。
"@
}

Write-Output "  WiX：$wixBin"
# 只在本进程里临时加进 PATH，不改系统环境变量
$env:PATH = "$wixBin;$env:PATH"

$msiArgs = @(
    '--type', 'msi',
    '--win-dir-chooser',      # 让用户挑安装位置
    '--win-menu',             # 开始菜单项
    '--win-menu-group', $appName,
    '--win-shortcut',         # 桌面快捷方式
    '--win-shortcut-prompt',  # 但由用户勾选，不硬塞
    '--win-upgrade-uuid', $upgradeUuid
)
if ($PerUser) {
    # 装进当前用户目录，整个过程不弹 UAC。
    # 代价是只有这个用户能用，「程序和功能」里也只有他看得见
    $msiArgs += '--win-per-user-install'
    Write-Output '  按「当前用户」安装（免 UAC）'
} else {
    Write-Output '  按「所有用户」安装（装的时候会要一次管理员权限）'
}

Write-Output '  jpackage msi…'
& jpackage @common @msiArgs
if ($LASTEXITCODE -ne 0) {
    Fail "MSI 生成失败。app-image 仍可用：$outDir\$appName"
}

$msiFile = Get-ChildItem $outDir -Filter '*.msi' | Select-Object -First 1
Write-Output ''
Write-Output "已生成：$($msiFile.FullName)"
Write-Output "        $([math]::Round($msiFile.Length / 1MB, 1)) MB"
Write-Output ''
Write-Output '注意：安装包没有代码签名，Windows SmartScreen 会拦一次'
Write-Output '（「更多信息 → 仍要运行」）。要消除这个提示需要一张代码签名证书，'
Write-Output '本脚本无法代劳。'
