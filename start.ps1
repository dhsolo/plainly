# 独立启动 Plainly。
#
# 与 run.cmd 的区别：run.cmd 占住当前控制台，进程随控制台一起结束；
# 这里用 Start-Process 让它脱离父进程独立运行，关掉终端也不受影响。
#
# 用法： powershell -ExecutionPolicy Bypass -File start.ps1
#        powershell -ExecutionPolicy Bypass -File start.ps1 -Restart   # 先停掉再起

param([switch]$Restart)

# $PSScriptRoot 是取脚本目录的标准写法；
# 用 $MyInvocation.MyCommand.Path 在某些调用方式下会拿到空值，
# 后面所有 Join-Path 就会静默退化成相对路径，排查起来很绕。
$root = $PSScriptRoot
if (-not $root) { $root = Split-Path -Parent $MyInvocation.MyCommand.Definition }
if (-not $root) { $root = (Get-Location).Path }

$jar = Join-Path $root 'plainly-app\target\plainly.jar'
$deps = Join-Path $root 'plainly-app\target\deps\*'
$errLog = Join-Path $root 'plainly-stderr.log'
$outLog = Join-Path $root 'plainly-stdout.log'

# 上一次的日志留一份。
#
# 原来每次启动都直接覆盖，于是「上次启动报了个异常」这件事永远查不了——
# 等有人来问的时候，那份日志早就被后面几次重启冲掉了。异常本身往往只在
# 某次启动时出现，覆盖掉就等于没记过。
foreach ($old in @($errLog, $outLog)) {
    if (Test-Path $old) {
        Move-Item -Path $old -Destination "$old.1" -Force -ErrorAction SilentlyContinue
    }
}

if (-not (Test-Path $jar)) {
    Write-Output "[错误] 未找到 $jar"
    Write-Output '请先执行： mvn -s settings.xml -gs settings.xml package'
    exit 1
}

# 找已在运行的实例。java 与 javaw 都要查——历史上两种都用过
function Get-PlainlyProcess {
    Get-Process java, javaw -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -and (Test-Path $_.Path) } |
        Where-Object {
            $cl = (Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)" -ErrorAction SilentlyContinue).CommandLine
            $cl -like '*com.plainly.app.Launcher*'
        }
}

$running = Get-PlainlyProcess
if ($running) {
    if ($Restart) {
        $running | ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 2
    } else {
        Write-Output "Plainly 已在运行，pid $($running.Id -join ', ')"
        Write-Output '要重启请加 -Restart'
        exit 0
    }
}

# -Dfile.encoding=UTF-8：本机平台编码是 GBK，不锁定的话中文会乱码
# 变量名不能叫 $args——那是 PowerShell 的自动变量，赋值会直接解析报错
$javaArgs = @(
    '-Dfile.encoding=UTF-8',
    '-Dsun.stdout.encoding=UTF-8',
    '-Dsun.stderr.encoding=UTF-8',
    '-cp', "$jar;$deps",
    'com.plainly.app.Launcher'
)

# 用 javaw 而不是 java：前者本来就不带控制台窗口。
#
# 千万别用 java 配 -WindowStyle Hidden 来藏控制台——那会把 JavaFX 的主窗口一起藏掉。
# Win32 规定进程第一次 ShowWindow 采用 STARTUPINFO 里的 wShowWindow 而忽略传入值，
# 于是 Stage.show() 建出来的窗口直接是隐藏的：进程活着，界面永远不出来。
Start-Process -FilePath 'javaw' -ArgumentList $javaArgs -WorkingDirectory $root -RedirectStandardError $errLog -RedirectStandardOutput $outLog

# 等主窗口真正出现，而不是等进程起来——进程活着不等于界面可见
$deadline = (Get-Date).AddSeconds(25)
$shown = $null
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 700
    $shown = Get-Process javaw -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowTitle -eq 'Plainly' }
    if ($shown) { break }
}

if ($shown) {
    Write-Output "已启动，pid $($shown.Id)，主窗口已显示"
} else {
    Write-Output '主窗口没有出现，stderr 末尾：'
    if (Test-Path $errLog) { Get-Content $errLog -Tail 15 }
    exit 1
}
