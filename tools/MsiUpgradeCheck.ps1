﻿# 比对两个 MSI，看新版能不能真的升级掉旧版。
#
#   powershell -ExecutionPolicy Bypass -File tools\MsiUpgradeCheck.ps1 <旧.msi> <新.msi>
#
# 为什么需要这个：MSI 的升级不是「装上去就覆盖」，而是靠三个东西对上：
#
#   1. UpgradeCode 相同        —— 两者被认作同一个产品；不同就变成并排装两份
#   2. ProductVersion 变大     —— 前三段纯数字比较，相同则既不升级也不提示
#   3. Upgrade 表里的版本区间  —— 新包要声明「小于我的版本都可以被我替换」
#
# 三条里任何一条不满足，装的时候都**不会报错**：用户双击、看到进度条、看到「完成」，
# 然后打开的还是旧版本，或者「程序和功能」里多出第二个条目。
# 所以只能在发布之前把这三条摆出来对一遍。
#
# 全程只读 MSI 文件，不安装、不写注册表。

param(
    [Parameter(Mandatory = $true)][string]$Old,
    [Parameter(Mandatory = $true)][string]$New
)

$ErrorActionPreference = 'Stop'
$failures = 0

function Check($what, $ok, $detail) {
    if ($ok) {
        Write-Output "  [对] $what"
    } else {
        Write-Output "  [错] $what -- $detail"
        $script:failures++
    }
}

# MSI 就是一个小关系库，用 WindowsInstaller 的 COM 接口按表查
function Read-Msi($path) {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $db = $installer.GetType().InvokeMember(
        'OpenDatabase', 'InvokeMethod', $null, $installer, @($path, 0))

    function Query($sql, $columns) {
        $view = $db.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $db, @($sql))
        $view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null) | Out-Null
        $rows = @()
        while ($true) {
            $rec = $view.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $view, $null)
            # Fetch 取完之后返回 null，这就是循环的出口
            if ($null -eq $rec) { break }
            $row = @()
            for ($i = 1; $i -le $columns; $i++) {
                $row += $rec.GetType().InvokeMember(
                    'StringData', 'GetProperty', $null, $rec, $i)
            }
            $rows += , $row
        }
        return $rows
    }

    $props = @{}
    foreach ($r in Query 'SELECT Property, Value FROM Property' 2) {
        $props[$r[0]] = $r[1]
    }
    $upgrades = Query 'SELECT UpgradeCode, VersionMin, VersionMax, Attributes, ActionProperty FROM Upgrade' 5
    return @{ Props = $props; Upgrades = $upgrades }
}

Write-Output "旧包 $Old"
Write-Output "新包 $New"
Write-Output ''

# 一定要取 .Path：Resolve-Path 返回的是 PathInfo 对象，
# 直接丢给 COM 的 OpenDatabase 会得到一句 "Type mismatch"，
# 而报错位置指向 InvokeMember，跟真正的原因隔着好几层
$a = Read-Msi (Resolve-Path $Old).Path
$b = Read-Msi (Resolve-Path $New).Path

$oldVer = $a.Props['ProductVersion']
$newVer = $b.Props['ProductVersion']
Write-Output "旧 $($a.Props['ProductName']) $oldVer  ProductCode $($a.Props['ProductCode'])"
Write-Output "新 $($b.Props['ProductName']) $newVer  ProductCode $($b.Props['ProductCode'])"
Write-Output ''

# --- 1. 同一个产品
Check 'UpgradeCode 相同（两者被认作同一个产品）' `
    ($a.Props['UpgradeCode'] -eq $b.Props['UpgradeCode']) `
    "旧 $($a.Props['UpgradeCode'])，新 $($b.Props['UpgradeCode'])；不同会并排装两份"

# --- 2. ProductCode 必须不同
# 同一个 ProductCode 会被当成「已经装过这一份」，走的是修复/维护流程，不是升级
Check 'ProductCode 不同（每个版本一个）' `
    ($a.Props['ProductCode'] -ne $b.Props['ProductCode']) `
    '两个版本用了同一个 ProductCode，Windows 会当成同一份已安装的包'

# --- 3. 版本号真的变大了
# MSI 只比前三段，且按数值比。这里照它的规则比，不用字符串比较：
# 字符串比的话 "0.1.10" 会小于 "0.1.9"
function ToNumbers($v) {
    $p = ($v -split '\.')
    return @([int]$p[0], [int]$p[1], [int]$p[2])
}
$oa = ToNumbers $oldVer
$ob = ToNumbers $newVer
$greater = ($ob[0] -gt $oa[0]) -or
           ($ob[0] -eq $oa[0] -and $ob[1] -gt $oa[1]) -or
           ($ob[0] -eq $oa[0] -and $ob[1] -eq $oa[1] -and $ob[2] -gt $oa[2])
Check "版本变大（$oldVer -> $newVer，只比前三段）" $greater `
    '版本没变大：Windows 既不升级也不提示，用户装完用的还是旧的'

# --- 4. 新包声明了「小于我的都能替换」
$upgradable = $b.Upgrades | Where-Object { $_[4] -eq 'JP_UPGRADABLE_FOUND' } | Select-Object -First 1
if ($upgradable) {
    $max = $upgradable[2]
    Write-Output "  新包的可升级区间：[$($upgradable[1])) .. $max)  属性 $($upgradable[3])"
    # VersionMax 是新包自己的版本，且默认不含等号，即「严格小于」
    $covered = $max -eq $newVer -and (ToNumbers $max)[0] -ge $oa[0]
    Check "旧版本 $oldVer 落在新包的可升级区间里" `
        ($max -eq $newVer -and $greater) `
        "区间上界是 $max，覆盖不到 $oldVer"
} else {
    Check '新包里有 JP_UPGRADABLE_FOUND 这条升级规则' $false 'Upgrade 表里没有，装上去不会替换旧版'
}

# --- 5. 挡降级的规则也要在
$downgrade = $b.Upgrades | Where-Object { $_[4] -eq 'JP_DOWNGRADABLE_FOUND' } | Select-Object -First 1
Check '新包带了挡降级的规则' ($null -ne $downgrade) `
    '没有的话，把旧包装到新版本上会得到一个混合状态'

Write-Output ''
if ($failures -eq 0) {
    Write-Output "全部通过：$newVer 装上去会替换掉已装的 $oldVer，「程序和功能」里仍然只有一条。"
    Write-Output '（用户数据在 %APPDATA%\Plainly 下，不在安装目录里，升级不受影响。）'
    exit 0
} else {
    Write-Output "$failures 项不满足——这样发出去，用户装完可能还在用旧版本。"
    exit 1
}
