#!/usr/bin/env bash
#
# 打包 Plainly —— Linux 与 macOS。Windows 那一侧是 package.ps1。
#
#   ./package.sh                     # 只出 app-image（一个可直接运行的目录）
#   ./package.sh --installer         # 再出本平台的安装包
#   ./package.sh --installer --type deb
#   ./package.sh --skip-build        # 复用已有的 mvn 产物
#   ./package.sh --version 0.2.0     # 临时覆盖版本号，用来做升级演练
#
# 为什么不做成一个脚本出三个平台的包：**jpackage 不能交叉编译**。
# 它生成的安装包里装着本平台的运行时镜像和本平台的启动器二进制，
# 在 Windows 上只能出 exe/msi，在 Linux 上只能出 deb/rpm，在 macOS 上只能出 dmg/pkg。
# 想一次出齐，就得有三台机器——.github/workflows/release.yml 干的就是这件事。
#
# 签名同样不在这里做：
#   · macOS 没有 Apple Developer ID 证书就签不了，Gatekeeper 会拦下来，
#     用户要在「系统设置 → 隐私与安全性」里点「仍要打开」；
#   · Linux 的 deb/rpm 可以用 GPG 签，但那要一把私钥，同样不该写进脚本。
# 和 Windows 那边的 SmartScreen 是同一类问题：拿不到证书就绕不过去，只能如实说明。

set -euo pipefail

app_name='Plainly'
main_jar='plainly.jar'
main_class='com.plainly.app.Launcher'
vendor='dhsolo'
copyright='Copyright 2026 戴虎 (dhsolo)'
description='跨数据库管理工具'

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out_dir="$root/dist"
build_dir="$root/build"
input_dir="$build_dir/package-input"

want_installer=0
skip_build=0
forced_version=''
forced_type=''

fail() { printf '\n%s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --installer) want_installer=1 ;;
        --skip-build) skip_build=1 ;;
        --version) forced_version="${2:-}"; shift ;;
        --type) forced_type="${2:-}"; shift ;;
        -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
        *) fail "不认识的参数：$1（--help 看用法）" ;;
    esac
    shift
done

# ---------------------------------------------------------------- 0. 平台

case "$(uname -s)" in
    Linux)  platform=linux  ;;
    Darwin) platform=mac    ;;
    *) fail "这个脚本只处理 Linux 和 macOS。Windows 请用 package.ps1。
当前 uname -s = $(uname -s)" ;;
esac

# 每个平台的默认安装包类型。deb 与 rpm 只能出一种是因为 jpackage 一次只接一个 --type，
# 要两种就跑两遍（release.yml 里就是跑两遍）
if [ -n "$forced_type" ]; then
    pkg_type="$forced_type"
elif [ "$platform" = linux ]; then
    # 默认 deb：Debian / Ubuntu 是最常见的桌面发行版。要 rpm 就 --type rpm
    pkg_type=deb
else
    pkg_type=dmg
fi

# ---------------------------------------------------------------- 1. 版本号
#
# 版本号只有一个来源：根 pom.xml。理由见 README「一处改版本号」——
# 两处不同步的后果不是报错，而是升级悄悄没发生。
#
# 取的是根 <project> 下那个 <version>。根 pom 没有 <parent>，
# 所以文件里第一个 <version> 就是它；用 sed 取第一个即可，不必引 XML 解析器。

pom_version() {
    local pom="$root/pom.xml"
    [ -f "$pom" ] || fail "没找到 $pom"
    local raw
    raw=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$pom" | head -n 1)
    [ -n "$raw" ] || fail 'pom.xml 里没读到 <version>'
    printf '%s' "${raw%-SNAPSHOT}"
}

if [ -n "$forced_version" ]; then
    version="$forced_version"
    echo "版本 ${version}（命令行指定，未采用 pom.xml 里的）"
else
    version="$(pom_version)"
    echo "版本 ${version}（取自 pom.xml）"
fi

case "$version" in
    *[!0-9.]*|'') fail "版本号 '$version' 里有非数字字符。
deb / rpm / dmg 都只接受 主.次.补丁 三段纯数字；-SNAPSHOT 已自动去掉，
其余后缀（-rc1、+build 之类）请先从 pom.xml 里去掉。" ;;
esac
echo "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' \
    || fail "版本号 '$version' 不是三段（如 0.2.0）"

# macOS 的额外限制：主版本必须 >= 1。
#
# jpackage 在 macOS 上把 --app-version 写进 CFBundleShortVersionString，
# 而苹果要求它的第一段是正整数。0.1.0 在 Linux 和 Windows 上都合法，
# 到 macOS 上会被 jpackage 直接拒绝。
#
# 这里提前查出来并说清楚，是因为 jpackage 自己的报错只会说版本号无效，
# 不会提「因为你在 macOS 上」——而同一个版本号在另外两个平台明明是好的。
if [ "$platform" = mac ]; then
    major="${version%%.*}"
    if [ "$major" -lt 1 ]; then
        fail "版本号 $version 在 macOS 上打不出安装包：主版本必须 >= 1（苹果对
CFBundleShortVersionString 的要求），而这里是 ${major}。

两条路：
  1. 把 pom.xml 的版本升到 1.x，三个平台统一；
  2. 只给 macOS 临时换一个号做验证：./package.sh --installer --version 1.0.0
     —— 但别拿它当正式发布，那样 macOS 的版本号会和另外两个平台对不上。"
    fi
fi

# ---------------------------------------------------------------- 2. 构建

if [ "$skip_build" -eq 0 ]; then
    echo '[1/4] 构建…'
    mvn -s "$root/settings.xml" -gs "$root/settings.xml" -q package
else
    echo '[1/4] 跳过构建'
fi

jar="$root/plainly-app/target/$main_jar"
[ -f "$jar" ] || fail "没找到 ${jar}，先跑一次构建（去掉 --skip-build）"

# 平台包拿错了不会在构建期报错，只在界面起来那一刻 UnsatisfiedLinkError。
# 这里直接查产物：deps 里必须有本平台的 JavaFX 包
deps_dir="$root/plainly-app/target/deps"
case "$platform" in
    linux) expect_jfx='javafx-graphics-*-linux.jar' ;;
    mac)   if [ "$(uname -m)" = arm64 ]; then
               expect_jfx='javafx-graphics-*-mac-aarch64.jar'
           else
               expect_jfx='javafx-graphics-*-mac.jar'
           fi ;;
esac
# shellcheck disable=SC2086
if ! ls $deps_dir/$expect_jfx >/dev/null 2>&1; then
    fail "deps 里没有本平台的 JavaFX（找 ${expect_jfx}）。
构建时 javafx.platform 选错了——pom 里按操作系统自动选，被覆盖过的话加上：
  mvn -Djavafx.platform=<win|linux|mac|mac-aarch64> package
拿错平台的包，编译和打包全程不报错，装完一启动就 UnsatisfiedLinkError。"
fi

# ---------------------------------------------------------------- 3. 素材

echo '[2/4] 收集 jar 与图标…'

rm -rf "$input_dir"
mkdir -p "$input_dir"
cp "$jar" "$input_dir/"
cp "$deps_dir"/*.jar "$input_dir/"

# 图标从代码里那份生成，不是仓库里另放一张——另放一张就意味着改了 Icons 之后
# 安装包上的图标不会跟着变，而且没有任何提示。三个平台各要一种格式
if [ "$platform" = linux ]; then
    icon_file="$build_dir/plainly.png"
else
    icon_file="$build_dir/plainly.icns"
fi
# 画图标要有图形环境。
#
# MakeAppIcon 是个 JavaFX Application——它得把矢量图标真画出来再快照，
# 而 JavaFX 在 Linux 上要连 X11。CI 的 runner 是无头的，于是这一步会抛
# "UnsupportedOperationException: Unable to open DISPLAY"，
# 报错里不会提「你缺一个显示服务」，只说打不开 DISPLAY。
#
# 用 xvfb-run 给它一个虚拟显示。-a 让它自己挑一个没被占用的显示号，
# 否则并发跑两次（deb 和 rpm 各跑一遍脚本）会撞在同一个 :99 上。
#
# macOS 和有桌面的 Linux 上不需要，直接跑。
icon_runner=()
if [ "$platform" = linux ] && [ -z "${DISPLAY:-}" ]; then
    if command -v xvfb-run >/dev/null 2>&1; then
        icon_runner=(xvfb-run -a)
    else
        fail "这台机器没有图形环境（DISPLAY 为空），也没有 xvfb-run。
画应用图标要用到 JavaFX，它在 Linux 上必须连 X11。装一个：
  sudo apt-get install -y xvfb     # Debian / Ubuntu
  sudo dnf install -y xorg-x11-server-Xvfb   # Fedora / RHEL"
    fi
fi

# ${arr[@]+"${arr[@]}"} 而不是 "${arr[@]}"：macOS 自带的是 bash 3.2，
# 在 set -u 下展开一个空数组会当成未绑定变量直接退出。
# 这一行在三个平台上都要跑得过，所以用这个兼容写法
${icon_runner[@]+"${icon_runner[@]}"} \
    java -Dfile.encoding=UTF-8 -cp "$jar:$deps_dir/*" \
         "$root/tools/MakeAppIcon.java" "$icon_file"
[ -f "$icon_file" ] || fail '图标生成失败'

modules_file="$root/tools/jpackage-modules.txt"
[ -f "$modules_file" ] || fail "没找到模块清单 $modules_file"
modules=$(sed 's/#.*$//' "$modules_file" | tr -d ' \t' | grep -v '^$' | paste -sd, -)
echo "  模块清单：${modules_file}（$(echo "$modules" | tr ',' '\n' | wc -l | tr -d ' ') 个）"

# ---------------------------------------------------------------- 4. app-image

common=(
    --name "$app_name"
    --app-version "$version"
    --input "$input_dir"
    --main-jar "$main_jar"
    --main-class "$main_class"
    --icon "$icon_file"
    --dest "$out_dir"
    --vendor "$vendor"
    --copyright "$copyright"
    --description "$description"
    --add-modules "$modules"
    # 平台默认编码不锁定的话中文会乱码。Linux 上多数发行版已经是 UTF-8，
    # 但最小化安装的容器里常常是 POSIX/C，那时候界面上的中文会变成问号
    --java-options '-Dfile.encoding=UTF-8'
    --java-options '-Dsun.stdout.encoding=UTF-8'
    --java-options '-Dsun.stderr.encoding=UTF-8'
)

mkdir -p "$out_dir"
echo '[3/4] jpackage app-image…'
jpackage "${common[@]}" --type app-image
if [ "$platform" = linux ]; then
    echo "  已生成：$out_dir/$app_name/bin/$app_name"
else
    echo "  已生成：$out_dir/$app_name.app"
fi

if [ "$want_installer" -eq 0 ]; then
    echo '[4/4] 未指定 --installer，到此为止'
    exit 0
fi

# ---------------------------------------------------------------- 5. 安装包

echo "[4/4] jpackage ${pkg_type}…"

extra=()
case "$pkg_type" in
    deb)
        extra=(
            --linux-shortcut                       # 桌面菜单里有一项
            --linux-menu-group 'Development'
            --linux-app-category 'database'
            # 维护者邮箱：deb 的控制字段要求有一个，缺了 dpkg-deb 直接拒绝
            --linux-deb-maintainer 'dhsolo@users.noreply.github.com'
            # 包名统一小写。不指定的话 jpackage 用应用名（Plainly），
            # 而 deb 的包名规范要求全小写——大写在部分工具链上会被拒
            --linux-package-name 'plainly'
        )
        ;;
    rpm)
        extra=(
            --linux-shortcut
            --linux-menu-group 'Development'
            --linux-app-category 'Applications/Databases'
            --linux-rpm-license-type 'ASL 2.0'     # Apache License 2.0 在 rpm 里的写法
            --linux-package-name 'plainly'
        )
        ;;
    dmg|pkg)
        extra=(
            # 反向域名的包标识。macOS 靠它认「这是同一个应用」，
            # 相当于 Windows 那边的 UpgradeCode——**不要改**，改了就是两个应用并存
            --mac-package-identifier 'com.plainly.app'
            --mac-package-name "$app_name"
        )
        ;;
    app-image)
        echo '  --type app-image 已经在上一步做过了'
        exit 0
        ;;
    *)
        fail "不认识的安装包类型：$pkg_type
Linux 支持 deb / rpm，macOS 支持 dmg / pkg。"
        ;;
esac

jpackage "${common[@]}" --type "$pkg_type" "${extra[@]}"

echo
echo '完成。产物：'
ls -lh "$out_dir" | tail -n +2

# 装完之后怎么验，见 README「打完之后怎么验」。
# 这里只提醒最要紧的一条：**在一台没有 JDK 的机器上装一遍**。
# 开发机上装出来的包哪怕漏了模块也照样能跑——它会去用系统里那套 JDK，
# 于是「漏模块」这个错误在开发机上永远不会暴露。
