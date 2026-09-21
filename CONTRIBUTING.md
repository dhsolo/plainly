# 参与开发

## 从零构建

需要 **JDK 17**（用 21 也能编，但 `maven.compiler.target` 是 17）和 **Maven 3.8+**。

```bash
cp settings.xml.example settings.xml
mvn -s settings.xml -gs settings.xml package
```

为什么带 `-s settings.xml`：机器全局的 `~/.m2/settings.xml` 常被配成某个镜像
`mirrorOf=*`，那个地址一旦不可达，依赖解析会挂起到超时——而且不报错，只是卡住。
项目自带的这份显式指向 Maven Central，把构建和机器全局配置隔开。

本机已经有依赖缓存的话，在 `settings.xml` 里写上 `<localRepository>`
就能完全离线构建（`mvn -o`）。

跑起来：

```bash
./run.cmd                                        # 占住当前控制台
powershell -File start.ps1                       # 脱离控制台，日志写到 plainly-*.log
powershell -File package.ps1 -Msi -FetchWix      # 出安装包
```

## 测试

```bash
mvn -s settings.xml -gs settings.xml test
```

全部测试都不需要外部数据库——关系库那部分跑在 H2 上，其余是纯单元测试。
**新增的测试也必须保持这一点**：需要真实实例才能跑的东西不是单元测试，
它属于下面说的探针。

推上去之后 `.github/workflows/ci.yml` 会在 **Windows、Linux、macOS 三个平台**
各跑一遍。三个都跑不是多余的：构建按操作系统挑 JavaFX 的平台包
（见 `plainly-app/pom.xml` 里那几个 profile），而挑错了不在构建期报错——
只在一个平台上跑绿，等于没验过另外两个。

## 探针：这个项目的一条硬规矩

`tools/` 下有几十个 `*Probe.java`。它们不是测试，是**拿真东西验证假设**的一次性程序。

存在的理由很直接：这个项目里最贵的错误，全都是「读代码推断出的结论」和
「真实行为」不一致造成的，而且它们**都不报错**。举几个真发生过的：

- Oracle 的注释写着「identity 子句要在列约束之后」——写反了，真机上直接 `ORA-03076`；
- 达梦的 `ALL_TRIGGERS` 没有 `TRIGGER_TYPE` 列，异常被吞掉，触发器列表就是空的；
- MongoDB 里 `"0123"` 被存成了 `123`，离线测试全绿，真机探针一跑就现原形；
- `updateOne` 没匹配到文档不算失败，返回 0 就完事，界面照常显示「已保存」。

所以约定是：

1. **改动涉及某个数据库的真实行为时，写一个探针跑一遍**，别只读文档和代码；
2. 探针要**先证明它拦得住这个 bug**——把修复临时改回去，看它是不是真的变红。
   一个从来没红过的检查，等于没有检查；
3. 探针里不写死凭据。用命令行参数或环境变量
   （例：`PLAINLY_MONGO_USER` / `PLAINLY_MONGO_PASSWORD`）；
4. 探针改数据库的话，自己建、自己删，不碰别人的数据；
5. **路径不写死。** 需要仓库里的文件（比如 `demo/plainly-demo` 那个演示库）时统一写成：

   ```java
   System.getProperty("plainly.home", ".") + "/demo/plainly-demo"
   ```

   默认按当前工作目录解析——探针本来就是在仓库根目录下跑的；
   要在别处跑就加 `-Dplainly.home=<仓库路径>`。

   这条是开源时补的：原来 29 个探针里写死的是某台机器上的绝对路径，
   别人克隆下来一个都跑不了。

## 代码里的约定

**精度是这个项目的地基。** 所有值以 `String` 承载，全程只有一次转换点：
JDBC 那边是 `CellReader`，MongoDB 那边是 `MongoValues`。
任何地方出现 `getDouble()`、`Double.parseDouble()` 去处理数据库里的数值，
都是 bug——`DECIMAL(38,10)` 和 Mongo 的 `Decimal128` 经过 double 会**安静地**变值。

**模块依赖是单向的**，不要反向引用：

```
plainly-driver-api  ←  plainly-driver-jdbc / -redis / -mongo
       ↑                        ↑
   plainly-core  ←──────────────┘
       ↑
   plainly-app
```

`plainly-driver-api` 不依赖任何第三方库，这一点是刻意的：精度那条规则要靠类型系统
落地，多一个依赖就多一个绕过它的口子。

**注释写「为什么」，不写「是什么」。** 代码本身说得清做了什么；
值得写下来的是那些踩过的坑——为什么不用看起来更自然的写法、
哪个假设被真机推翻过。这个仓库里大量注释是这种，请保持。

## 加一个新数据库

按顺序，一处都别漏（漏了多半不报错）：

1. `DbType` 里加一项；
2. `Connections.open` / `test` 里分发；
3. `DbMarks` 的字母**必须唯一**（`DbMarksTest` 会拦）；
4. 界面上按能力分支的地方——**别拿 `hasTableStructure()` 当「是不是某一家」的替身**。
   加 MongoDB 时就踩过：那个判断以前恰好等价于「是不是 Redis」，
   多一家之后立刻把连接强转成了键值接口；
5. 重跑 jdeps 核对 `package.ps1` 里的 `--add-modules`。新驱动常常需要
   静态分析看不见的模块（MongoDB 需要 `java.security.sasl`，漏了只在装完的版本上炸）；
6. 写探针，在真实实例上跑通；
7. README 里如实标注验证状态——没在真机验过就写「未验证」，不要含糊过去。

## 提交

这个仓库刚开源，还没有固定的分支与评审流程。在建立之前：

- 一个提交只做一件事，说明写清「为什么」；
- 跑一遍 `mvn test`；
- 改了某个数据库的行为，附上探针的输出。
