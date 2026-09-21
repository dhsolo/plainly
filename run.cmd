@echo off
REM 启动 Plainly。
REM
REM 走 classpath 而不是 module-path：主类 Launcher 不继承 Application，
REM 因此不会触发 "JavaFX runtime components are missing"。
REM
REM 先执行一次构建：
REM   mvn -s settings.xml -gs settings.xml package -DskipTests

setlocal
set APP_DIR=%~dp0plainly-app\target

if not exist "%APP_DIR%\plainly.jar" (
  echo [错误] 未找到 %APP_DIR%\plainly.jar
  echo 请先执行： mvn -s settings.xml -gs settings.xml package -DskipTests
  exit /b 1
)

REM -Dfile.encoding=UTF-8：本机平台编码是 GBK，不锁定的话中文会乱码
java -Dfile.encoding=UTF-8 ^
     -Dsun.stdout.encoding=UTF-8 ^
     -Dsun.stderr.encoding=UTF-8 ^
     -cp "%APP_DIR%\plainly.jar;%APP_DIR%\deps\*" ^
     com.plainly.app.Launcher %*

endlocal
