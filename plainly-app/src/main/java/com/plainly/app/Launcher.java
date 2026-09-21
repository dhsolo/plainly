package com.plainly.app;

/**
 * 启动入口。
 *
 * <p>刻意不让 main 所在的类继承 {@code Application}：
 * JVM 一旦发现主类是 Application 的子类，就会走模块化的 JavaFX 启动路径，
 * 在 classpath 模式下报 “JavaFX runtime components are missing”。
 * 隔一层就绕开了，代价是多一个五行的类。
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        PlainlyApp.main(args);
    }
}
