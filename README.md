# Plainly

▎ A cross-database desktop client that never lets a value pass through a double — DECIMAL(38,10), Int64 and Decimal128 stay exact end to end. MySQL, PostgreSQL, Oracle, SQL Server, DM, SQLite, H2, Redis, MongoDB.

跨数据库管理工具。JavaFX 桌面应用，出 Windows、Linux、macOS 三个平台的安装包。

> 三个平台的**打包**都跑得通，但验证程度不一样：Windows 上是一路真机用下来的；
> Linux 与 macOS 目前只在 CI 上打得出包，装完之后的实际表现还没有在真机上验过。
> 具体差异见「已知边界」第 14–16 条——尤其是保存的口令在非 Windows 平台上
> 还没有真正加密。

> 产品名 **Plainly** 是占位名，定了正式名字全局替换即可。

---

## 快速开始

```bat
REM 首次：从模板生成本机的 Maven 配置（settings.xml 不进版本库，见下方说明）
copy settings.xml.example settings.xml

REM 构建（首次会从 repo1 拉依赖，见下方「关于 Maven 配置」）
mvn -s settings.xml -gs settings.xml package

REM 启动
run.cmd
```

Linux / macOS 上：

```bash
cp settings.xml.example settings.xml
mvn -s settings.xml -gs settings.xml package
java -cp "plainly-app/target/plainly.jar:plainly-app/target/deps/*" com.plainly.app.Launcher
```

JavaFX 的平台包按操作系统自动选，不用额外传参数（见「打包」一节）。

启动后点「新建连接」→ 数据库选 **H2** → 库文件填 `demo/plainly-demo`（相对仓库根目录）
→ 测试连接 → 保存。左侧展开即可看到 `ORDERS`（5006 行）与 `CUSTOMERS`。

演示库里前几行是刻意构造的精度用例：BIGINT 上界、`DECIMAL(38,10)` 满位、`0.0000000001`。
打开表就能看到网格、单元格面板、导出对话框在这些值上的真实表现。

重新生成演示库：

```bat
java -cp D:\mavenLib\com\h2database\h2\2.1.214\h2-2.1.214.jar tools\MakeDemoDb.java demo/plainly-demo
```

---

## 这一版做了什么

**浏览与编辑**

| 界面 | 说明 |
|---|---|
| 连接树 | 连接 → 库 → 表 / 视图，懒加载；分组目录、类型色标、表注释、行数估算 |
| 数据网格 | 分页、筛选与排序、单元格原始值面板、点列头排序（下推给数据库） |
| 数据编辑 | 双击改值、脏值标记、参数化写回；Ctrl+V 粘贴进编辑缓冲，按「保存」才落库 |
| 增行 / 删行 | 网格里加空行、标记删除，和改值走同一条路——按「保存」才发 INSERT / DELETE |
| 手动事务 | 自动提交开关 + 提交 / 回滚。跑完 UPDATE 看清影响行数再决定留不留 |
| 代码片段 | 可复用的写法，敲前缀就从补全里出来；出厂自带分页、找重复行这几条 |
| SQL 编辑器 | 语法着色、作用域感知补全、执行 / 取消、执行计划、查询构建器 |
| 标签页 | 保存、收藏、右键批量关闭；直接关掉工具时下次启动可恢复未保存的现场 |
| 检查更新 | 出厂关闭、地址留空，此时不发任何网络请求；打开后只提示并帮你打开发布页，不下载、不执行 |

**结构**

| 界面 | 说明 |
|---|---|
| 表结构设计器 | 改字段、加删列、改名、换主键，预览 ALTER 后执行 |
| 索引 / 外键 | 新建、修改、删除 |
| 触发器 | 按 BEFORE/AFTER × INSERT/UPDATE/DELETE 分类列出与新建 |
| 视图 / 函数 / 存储过程 | 查看与编辑定义 |
| 复制表 | 照着一张表建新表，可连数据一起，索引自动改名、自增计数器自动跟上 |
| ER 图 / 数据模型 | 关系图，可导出 PNG |
| 虚拟外键 | 给没建物理外键的库补上关系标注，只存本机，ER 图和外键栏都认它 |
| 序列 / 事件 / 物化视图 | 树上单列一类，可看定义（PG、Oracle 的序列，MySQL 的 EVENT） |
| 数据字典 | 整库结构导成 HTML / Markdown / xlsx，能直接发出去 |

**数据流转**

| 界面 | 说明 |
|---|---|
| 导入向导 | CSV / JSON（含 NDJSON）/ SQL 脚本；字段映射、试运行、失败行落盘 |
| 数据导出 | CSV / xlsx / JSON / SQL INSERT，含长数值处理；查询结果可重新执行并游标流式导出全部 |
| 结构同步 | 两库比对、生成同步脚本、按对象勾选执行 |
| 数据同步 / 数据传输 | 跨连接的表结构与数据搬运 |
| 备份与还原 | 整库导出成 SQL 脚本再导回 |

**运维与安全**

| 界面 | 说明 |
|---|---|
| 连接管理 | 新建 / 编辑 / 删除、测试连接、DPAPI 存密码、只读模式、标记色、分组 |
| 影响面拦截 | 执行前认出没有 WHERE 的 UPDATE / DELETE 与 DROP DATABASE，问一句再跑 |
| 计划任务 | 定时执行脚本、导出、<b>备份整库</b>，按份数保留，可发邮件通知 |
| 服务器状态 | 连接数、慢查询这类实例指标；可结束卡住的会话（MySQL / PG） |
| 用户与权限 | 谁能动哪张表，只读查看（MySQL / PG） |
| 连接配置迁移 | 导出 / 导入连接，口令可选用一个新口令重新加密 |
| 亮色 / 暗色主题 | 整套配色收在样式令牌里，切换当场生效 |
| 失效策略 | 查询历史与任务运行记录自动限量，收藏的不受限 |

支持的数据库：MySQL / MariaDB、PostgreSQL、SQLite、H2、Oracle、达梦 DM8、SQL Server、
Redis、MongoDB、人大金仓 KingBase、OceanBase、GaussDB / openGauss。

Oracle、达梦、MongoDB 与 GaussDB / openGauss 已在真实实例上跑过一致性自检（见下）。
SQL Server、金仓、OceanBase 手上没有实例可验，**界面上的类型名如实标着「未验证」**——
方言、URL、驱动都接好了，但本项目的准入门槛是 `PrecisionConformanceTest` 在真机上跑通，
没跑过就只能算「已接线」。

---

## 模块结构

```
plainly-driver-api    驱动契约 + 差异引擎。零第三方依赖，精度铁律在这里靠类型系统落地
plainly-driver-jdbc   JDBC 实现 + 各库方言 + 精度与 DDL 的守门测试
plainly-driver-redis  Redis 实现。它没有表和列，所以单独一个模块，不去硬套 JDBC 那套契约
plainly-driver-mongo  MongoDB 实现。同样不走 JDBC，但用官方驱动——Mongo 的线协议带 BSON、
                     SCRAM 认证和拓扑发现，自己实现划不来（Redis 的 RESP2 只有五种回复类型）
plainly-core          连接注册表、凭据存储、查询调度、导入导出、同步、界面偏好。不依赖 JavaFX
plainly-app           JavaFX 界面与启动器
```

依赖方向严格单向：`app → core → driver-jdbc / driver-redis → driver-api`。

---

## 精度是怎么保证的

这是整个项目的硬约束，不是特性。

**一、类型系统禁止有损载体。** `Row` 的值是 `String[]`，
本类没有也永远不会有 `getInt()` / `getDouble()`。
`ResultSet` 把有损和无损的取值方式并排摆着，是最常见的翻车点；
契约层把它们去掉，调用方就没有机会用错。

**二、全项目只有一个转换点。** `CellReader` 是「数据库值 ↔ 文本」的唯一出入口：

```java
case EXACT_NUMERIC, INTEGER -> {
    BigDecimal v = rs.getBigDecimal(index);
    return rs.wasNull() ? null : v.toPlainString();  // 不是 toString()
}
```

用 `getBigDecimal` 而非 `getString`：两者按规范都无损，但 `getString` 在部分驱动上
受会话本地化影响（Oracle 的 NLS 能把小数点变成逗号）。
用 `toPlainString` 而非 `toString`：后者对大指数会输出 `1.2E+19`。

**三、写回与读取对称。** 全参数化，精确数值绑定为 `BigDecimal`，
由服务端按目标列类型做十进制解析。值绝不拼进 SQL 文本。

**四、脏值判断用字符串比较。** 数值比较会把 `1.10` 和 `1.1` 判成「没改」，
悄悄丢掉用户的编辑。Java 里这个坑还有个更出名的形式：
`new BigDecimal("1.10").equals(new BigDecimal("1.1"))` 是 `false`，
而 `compareTo` 是 `0` —— 两个方法选错哪一个都出事。索性不比数值。

**五、导出环节把选择权交给用户。** xlsx 的数值单元格底层就是 IEEE 754 双精度，
**这是文件格式的限制，与用什么语言写无关**。所以不替用户默默决定，
而是拿当前数据实算出损失量摆在他面前，默认写文本。

**六、真正的保证是 CI 里的测试。**
前五条都可能被一次依赖升级悄悄推翻，而精度丢失是**静默**的——
数据看着还在，只是尾巴没了，事后无法从结果里发现。

```bat
mvn -s settings.xml -gs settings.xml -pl plainly-driver-jdbc test
```

`PrecisionConformanceTest` 覆盖 BIGINT 边界、`DECIMAL(38,10)` 满位、极小值、
NULL 与空串的区分、参数化往返、以及「结果集取不到主键时降级为只读」。
其中一条测试专门反证用例值确实超出 double 的表示能力，
避免前面的断言只是因为用例太温和而碰巧通过。

**新增数据库驱动的准入门槛是通过这套测试，不是「能连上」。**

---

## 结构变更是怎么保证的

同一套思路，换了个战场。

**预览即执行。** 预览里的语句和点「应用」时跑的语句来自同一次 `TableDiff.compute`，
不存在看到一套、跑另一套的可能。

**做不到就明说。** `SqlDialect.supports(change)` 让每个方言如实声明能力边界。
SQLite 改不了列定义，设计器直接标红并禁用「应用」——
生成一条注定失败的 SQL 比直接说不支持更糟。

**整批变更在一个事务里。** 中途失败整体回滚，不会留下半套结构。

**测试分两层。**
`DdlGenerationTest` 验证「拼出来的字符串对不对」（MySQL 的 `CHANGE COLUMN`、
PostgreSQL 的 `USING` 转换、SQLite 的拒绝行为）；
`DdlApplyTest` 与 `SchemaSyncTest` 把生成的 DDL 拿到 H2 上**真跑一遍**——
语法拼对了但方言细节错了的情况很常见，只有真跑才知道。

结构同步那套测试守的是一条不变量：**把计划应用到目标库之后，再比一次必须为空**。
它一次覆盖了「差异算得全不全」「DDL 生成对不对」「数据库认不认」三件事，
任何一环出问题第二次比对都会剩下东西。

这一层真跑的测试当场抓到过两个缺陷，都是同一个根因的两面：
`VARCHAR(255)`、`DECIMAL(38,10)`、`TIMESTAMP(6)` 的括号参数看着都是「长度」，
实际是字符数、十进制位数、秒小数位三件不同的事，取值范围也不同。

- 把 `VARCHAR(255)` 改成 `TIMESTAMP` 时长度被原样继承，生成了 `TIMESTAMP(255)`；
- H2 对 `TIMESTAMP` 报 `COLUMN_SIZE=26`（字符串宽度）和 `DECIMAL_DIGITS=6`（秒小数位），
  元数据侧和草稿侧各取一个，导致一打开设计器就冒出一条根本不存在的变更。

现在由 `TypeNames.LengthKind` 统一区分三种语义，
并有一条不变量测试守着：**原样打开一张 17 种类型的表，必须产生零变更**。

---

## 结构同步

单表设计器的差异引擎扩到整库，没有另起炉灶：

```
TableDiff   一张表的字段差异        ← 结构设计器用
SchemaDiff  一个库的对象差异        ← 结构同步用，内部调 TableDiff
```

这意味着设计器里验证过的每条规则（括号参数的三种语义、主键隐含非空、风险分级）
在结构同步里自动成立。反过来，两边共用一套 `SqlDialect.ddlFor`，
新增一种方言只需实现一次。

界面上有两处刻意的默认值：**删表与删索引默认不勾选**。
方向是「以源为准」，但源里没有不等于该删——很可能只是源库还没同步过来。
不可逆的操作不该靠默认值替用户决定。

试一下：

```bat
REM 造一对结构有差异的演示库（可重复执行，会先复位目标库）
java -cp %H2JAR% tools\MakeSyncDemo.java demo

REM 界面里：工具条 →「结构同步」→ 源选 test、目标选 prod → 开始比对
run.cmd

REM 或者走命令行跑完整回路
java -cp "plainly-app\target\plainly.jar;plainly-app\target\deps\*" tools\SyncCheck.java
```

其中 `%H2JAR%` 是 `D:\mavenLib\com\h2database\h2\2.1.214\h2-2.1.214.jar`。

---

## 一个必须说清楚的限制：DDL 不一定能回滚

原先设计器和同步页的界面上都写着「变更在一个事务内执行，失败整体回滚」。
**这是句假话**，是写 `SchemaSyncTest` 时被测试当场戳破的：

MySQL、Oracle、H2 执行 DDL 时会**隐式提交**。一批变更跑到一半失败，
前面成功的那些已经生效且撤不回来。只有 PostgreSQL、SQLite、SQL Server 支持事务性 DDL。

更难堪的是，`DdlApplyTest` 里原本有一条断言「整批回滚」的测试，**它一直是绿的**——
因为变更的执行顺序让失败发生在建表之前，那条回滚断言从未被真正触发过。
一条空过的测试比没有测试更糟：它让人以为这件事验过了。

现在的做法：

- `SqlDialect.supportsTransactionalDdl()` 让每个方言如实声明，**默认 false**
  ——承诺一个做不到的回滚，比不承诺危险得多；
- `DbConnection.executeDdlBatch()` 按能力选择执行方式，失败时抛
  `DdlBatchException`，其中明确写出「整批已回滚」还是「前 N 条已生效且不可撤销」；
- 界面文案跟着能力走。在 MySQL 上，设计器底部写的是
  「该数据库的 DDL 会隐式提交，无法回滚；失败时会告知已执行到第几条」，
  确认框里也会再说一遍。

这条限制没法用代码绕过，只能如实告诉用户。藏起来的话，
等到某天真在生产库上失败了，用户会以为库还是原样——那才是真正的事故。

---

## 手动事务作用于整条连接，不是某个标签页

「跑一条 UPDATE，看清影响了几行，再决定要不要留下」——这件事本来做不到，
语句发出去就已经落库了。现在 SQL 编辑器和表页上都有一组「手动事务 / 提交 / 回滚」。

**它的作用范围是整条连接。** 一条连接配置对应一个物理连接（见 `AppContext`），
这条连接上开着的所有标签页共用同一个事务：在 A 页开了手动事务，在 B 页的网格里
改数据按「保存」，那些改动也进同一个事务、同样要按「提交」才落库。

Navicat 的做法是每个标签页各持一条物理连接，各管各的事务。那样更贴近直觉，
代价是连接数翻几倍、每条连接的当前库要各自维护。这里选了另一条——
一条连接一个事务，但**把这件事在界面上说明白**：开关旁边常驻一句话，
有未提交改动时整条控件变色，退出程序和断开连接之前都会拦一次。

### 三个不说就会出事的地方

**一、`setAutoCommit(true)` 会隐式提交。** 用户点这个开关的意思是「不想再手动管了」，
不是「把刚才那些都提交」。所以手上还有未提交改动时，驱动层直接**拒绝**这次切换，
让他先明确提交或回滚。

**二、内部的批量执行不能碰用户的事务。** 导入、结构同步这些功能内部都要开事务，
而用户可能正开着自己的事务。原来的写法是 `setAutoCommit(false)` → 干活 → `commit()`，
那会把用户手上还没想好的改动一起提交掉。现在这种情况下改走**保存点**：
这一批仍然是原子的，失败只退回到这一批开始之前，用户之前做的事一点不动。

**三、DDL 会隐式提交。** MySQL、Oracle、H2 上，一条 `ALTER TABLE` 会把当前事务提交掉。
带着未提交的事务去改结构，等于替用户做了一次他没同意的提交，而且撤不回来。
所以这种组合会被当场拦下，让他先了结手上的事务。

这三条都有测试守着（`ManualTransactionTest`），因为三条都属于「不报错，只是结果不对」。

---

## MongoDB 的实测

在 MongoDB 7.0.40 上跑 `tools/MongoLiveProbe.java`，22 项全过。这套探针不只测转换规则，
它<b>真写一遍再读回来</b>——因为这条路上最危险的失败是安静的：`updateOne` 没匹配上
不算错，返回 0 就完事，界面照常显示「已保存」，而库里什么也没变。

### 「表 / 行 / 列」在这里各对应什么

| 通用概念 | MongoDB |
| --- | --- |
| 模式 | 数据库 |
| 表 | 集合 |
| 行 | 一篇文档 |
| 列 | **抽样若干篇文档凑出来的字段并集** |

最后一条是和关系库最本质的差别：**那些列不是集合的定义**。同一个集合里两篇文档的字段
可以完全不同，网格只能展示抽到的那些。所以 `hasTableStructure()` 是关的，界面上不会
出现「表结构」页去暗示存在一份字段定义；要看一篇文档的全貌，双击那一格。

### 类型上真正会咬人的两处

**一、Decimal128 和 Double 在界面上长得一模一样。** 钱存在前者里，34 位十进制；
后者是 IEEE 754。读成 double 再打印，`9.99` 会变成 `9.9900000000000002`，且是安静地变。
`MongoValues` 对 Decimal128 只做一件事：取 `BigDecimal` 再 `toPlainString()`。

**二、改一个值不该顺带改它的类型。** 网格里所有值都是字符串，写回时必须变回 BSON，
而文本 `"123"` 单看是没有类型的。一个原本存字符串的字段，若按「看着像数字就存成数字」
写回去，这个字段的类型就被这次编辑悄悄改掉了，之后所有按字符串查它的地方都查不到。
所以改已有字段时**照它原来的类型转**；只有插入新文档、没有原类型可参照时才按写法推断。

推断规则里有两条排除，都是真机探针撞出来的，离线测试当时全绿：

- **带前导零的不算数字。** `"0123"` 存成 `123` 之后前导零永远找不回来，
  而带前导零的数字串几乎从来不是数字——是邮编、工号、订单号、银行卡号。
- **带正号的不算数字。** 电话号 `+8613800138000` 正好 14 位，落在 Int64 范围里，
  悄悄变成数字之后前面那个加号也没了。

猜错的代价不对称：把数字存成字符串，用户看得见（排序不对劲），改回来也容易；
把标识串存成数字，丢掉的字符找不回来。

### 这一版做了什么、没做什么

做了：连接（主机端口，或直接粘 `mongodb://` 连接串）、库与集合树、文档网格与分页、
筛选下推（翻成 Mongo 的查询文档）、排序下推、按 `_id` 写回的增删改。

没做：索引管理、聚合管道、导入、查找替换。后两条在关系库上依赖固定的列集合，
而集合里每篇文档的字段可以不一样——也许能跑通，但没在真机上验过，
摆出来就是在承诺一件没验证过的事。

### 一处刻意的取舍

**认证默认走 `admin` 库。** Mongo 的用户是建在某个库里的，认证要去那个库验，
而它和你想浏览的库常常不是同一个。拿业务库去认证会得到「Authentication failed」，
而账号密码明明是对的。要用别的库认证，在「主机」那一格填完整连接串并带上
`?authSource=库名`——副本集、TLS 这些也只能靠连接串表达，拆成界面控件既拆不全，
也会在某个选项上和官方语义对不上。

---

## openGauss 的精度实测

在 openGauss 5.0.0 上跑 `tools/PrecisionProbe.java --direct GAUSSDB ...`：
**21 项通过、0 不通过、1 项厂商行为**。

那一项是「空字符串被读成 NULL」，而它值得单独说，因为它改掉了一条判据的写法。

openGauss 的这个行为取决于建库时的 `DBCOMPATIBILITY`：默认的 **A 模式（Oracle 兼容）**
下空串就等于 NULL，同一个 openGauss 建成 PG 模式时两者是分开的。实测：

```
SELECT datcompatibility ...  →  A
SELECT '' IS NULL            →  t
SELECT length('')            →  null
```

原来这条检查把「哪几家把空串当 NULL」写死成了 Oracle 和达梦。照那个写法，
要么 openGauss 被误报成缺陷，要么把它加进名单——**而加进名单的代价是，
PG 模式下真出了问题也会被记成「厂商行为」，把真缺陷盖掉**。

所以改成直接问服务端：它自己说空串是 NULL，那就是它的语义；它说不是，
那读出 null 就是我们的问题。这个判据对任何一家都成立，不需要维护名单。

改完之后回归验证过三家：

| | 结果 | 空串那一条 |
| --- | --- | --- |
| MySQL 8.0.46 | 22 通过 / 0 不通过 | 通过（空串不是 NULL） |
| Oracle 23ai | 18 通过 / 0 不通过 / 5 厂商行为 | 厂商行为，和改动前一致 |
| 达梦 8.1.2 | 22 通过 / 0 不通过 | **通过** |

最后一行顺带纠正了一个错了很久的说法。原来那份名单写的是「Oracle 和达梦」，
而真机一问才发现达梦并不这样：

```
达梦 8.1.2    SELECT '' IS NULL FROM DUAL  ->  0
Oracle 23ai   SELECT '' IS NULL FROM DUAL  ->  1
```

那句话能错这么久，正是因为它**从来没被执行到**——达梦上空串本来就读得回来，
走的是通过那一支，名单里那一项是死的。写死的判据不只是不灵活，
它还会把错误的认知一直留在注释里。

同一个道理后来又用上了一次：`PrecisionCheck` 里「结果集可编辑」那两条原本
按「这一家声明自己报不报表名」判断。金仓上这个静态声明不成立——
报不报取决于**服务端版本**，V8R3 问不出来、V8R6 问得出，而方言只有一个。
改成看**这次到底有没有拿到表名**。牙齿也留住了：拿到了表名却仍定位不到主键，
那是我们的缺陷，照记不通过。

### 它的内核停在 PG 9.2

精度过了，但建表过不去。在 openGauss 上新建一张带自增主键的表会报：

```
ERROR: syntax error at or near "BY"
  "id" BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL
```

原因是 openGauss 自报的服务端版本就是 **9.2.4**——identity 列是 PG 10 才加的。
它不是「新版 PG 的一个分支」，分叉点在 PG 10 之前，PG 10 之后的东西一概没有。
于是所有继承自 PostgreSQL 方言、又只在新版 PG 上验过的写法都得逐条实测。

已经查实并覆写的三处（`Dialects.GaussDialect`，实测于 openGauss 5.0.0）：

| 做什么 | PG 10+ 的写法 | openGauss 的反应 | 改用 |
| --- | --- | --- | --- |
| 建表带自增列 | `GENERATED BY DEFAULT AS IDENTITY` | `syntax error at or near "BY"` | `bigserial` / `serial` / `smallserial` |
| 加一个自增列 | `ADD COLUMN id bigserial` | `not supported to alter table add serial column` | 建序列 + 设默认值 + **回填** + `OWNED BY` |
| 推进自增计数器 | `ALTER TABLE ... ALTER COLUMN ... RESTART WITH` | 语法不认；退一步的 `ALTER SEQUENCE ... RESTART` 答 `not yet supported` | `setval(seq, n, false)` |

有两个细节，错了都不会当场报错：

- `setval(seq, n)` 的意思是「**已经**发到 n 了」，下一个发 n+1。而调用方传进来的是
  「下一个要发的值」，所以第三个参数 `false` 不能省——漏了会白白跳过一个号。
- 加列时那一步**回填**（`UPDATE ... WHERE id IS NULL`）不能省。默认值只对之后插入的行
  生效，已有行会留 NULL；而真 PostgreSQL 上加 identity 列会重写整表、把已有行也编上号。
  少了回填，同一个操作在两家上结果不一样。

一次发六条语句是安全的：openGauss 的 DDL 是事务性的（实测：中途故意写错一条，
列和序列都没留下）。

除自增外，把 GaussDB 方言生成的**每一条** SQL 都发到真机上跑了一遍
（`tools/GaussSweepProbe.java`，28 条全通过）。一条条等用户撞出来代价太高，
而且每一条都是拦在半路上的错。扫出来的另外几处：

| 做什么 | 原来 | openGauss 的反应 | 改用 |
| --- | --- | --- | --- |
| 挂触发器 | `EXECUTE FUNCTION`（PG 11+） | `syntax error at or near "FUNCTION"` | `EXECUTE PROCEDURE`（PG 7 至今都认） |
| 建函数 + 挂触发器 | 两条拼一个字符串发 | `cannot insert multiple commands into a prepared statement` | 由方言自己拆成两条 |
| 列序列 / 序列属性 / 序列详情 | `pg_sequences`（PG 10+ 的视图） | `relation "pg_sequences" does not exist` | `pg_class` + `pg_sequence_parameters()` + `pg_sequence_last_value()` |

序列那一处有个拿不准的地方，取的是安全那一侧：openGauss 的
`pg_sequence_last_value` 返回 `(cache_value, last_value)`，**没有 `is_called`**，
单条查询里分不开「没用过」和「用过一次」。所以一律按「用过」算
（`last_value + increment`）——用过的算得准，没用过的多跳一个步长。
方向不能反：多跳只是白白空掉一个号，少算会让恢复出来的序列重发已经用过的号，
直到某次插入撞上主键冲突。

### 顺带修掉一个「建得成功、但什么都不做」

扫的过程中发现 PostgreSQL 方言接了 `body` 参数却**一个字都没用**：
用户在触发器编辑器里写什么都会被丢掉，生成的函数永远只是 `RETURN NEW`。
表现是触发器建得好好的、列表里也看得到，就是不干活——比报错难发现得多。
PG 家族都受影响，已修，并且把「真插一行看触发器有没有执行」写进了
`tools/TriggerProbe.java`：光比对 DDL 文本看不出这一条。

### 还有一处：打开「修改触发器」，看到的不是自己写的东西

编辑框回填用的是元数据里的「触发器体」那一栏。PostgreSQL 上那一栏
（`information_schema.triggers.action_statement`）只有一句
`EXECUTE FUNCTION f()`——逻辑在函数里，不在触发器上。

看着只是显示不对，**存回去才是真的坏**：那一句会被当成函数体塞进
`CREATE OR REPLACE FUNCTION ... AS $$ ... $$`，把用户原来的逻辑顶掉，
而且全程不报错。一次「打开看看又关掉」就足以让触发器失效。

改成顺着 `pg_trigger.tgfoid` 取那个函数的 `prosrc`——那才是
`createTriggerDdl` 放进 `$$` 里的东西，读出来和写回去对得上。
时机和事件仍从 `information_schema.triggers` 取（那边一个事件一行，
和界面上单选的形状一致）。`pg_class` 那一跳把模式一起限定死，
只按表名连的话，别的模式里有同名表就会连出重复行。

### 还有一处：改触发器整个走不通

对话框按「整段是不是以 `CREATE OR REPLACE` 开头」来决定要不要先发 `DROP`。
PostgreSQL 那段的开头正是 `CREATE OR REPLACE FUNCTION`——可那个 `OR REPLACE`
管的是**函数**，挂触发器用的还是光秃秃的 `CREATE TRIGGER`，撞名一定被拒。

判错的后果有两个，方向还相反：

- **改已有触发器**：以为不必先删，于是不发 `DROP`，接着报
  `trigger "..." already exists`。而且就算忽略这条错，**跑的仍是旧逻辑**——改了个寂寞。
- **新建时撞名**：本该直接拦下说「换个名字」，却弹出「会覆盖掉原来那个，确定继续？」。
  用户点了继续，撞回来的是数据库的原始报错——**对话框承诺了一件做不到的事**。

判据挪到了 `TriggerNames.replacesExisting`，认的是 `CREATE OR REPLACE TRIGGER`
这一整句，而且按词比对（`CREATE OR REPLACE TRIGGERS_LOG` 不算）。
会发这一整句的只有 Oracle 和达梦，正是真会静默覆盖的那两家。

同时 PG 的触发器体模板补上了 `RETURN`。原来用的是默认模板
`BEGIN ... END`，那是 MySQL 的形状；在 plpgsql 里它返回 NULL，
而 **BEFORE 触发器返回 NULL 的含义是丢弃这一行**——
一个本意只想记条日志的触发器，会安静地把插入的数据吃掉。

---

## 人大金仓：V8R3 与随包驱动对不上

在 KingbaseES **V008R003** 上一连就报：

```
ERROR: relation "PG_CATALOG.PG_namespace" does not exist
语句：listSchemas
```

这条 SQL 不是本工具发的——`listSchemas` 走的是 `DatabaseMetaData.getSchemas()`，
查询由驱动自己拼。实测下来病因很清楚：

```
服务端  Kingbase V008R003   系统目录是 sys_catalog.sys_namespace（pg_* 根本不存在）
驱动    V008R006B0001       按 pg_catalog 拼查询
模式名  PUBLIC / SYS_CATALOG …   全大写，case_sensitive = off
```

金仓 V8R6 起把系统目录改成了和 PostgreSQL 一致的 `pg_*`，V8R3 那一代叫 `sys_*`。
**两代对不上，元数据一条都读不出来**——连接本身是通的，口令也是对的，
所以很容易被当成权限问题去排查。

### 换驱动这条路是堵死的

官方能拿到的最老驱动就是 V8R6。12 个可获得的版本全部真连过一遍：

```
V8R6 驱动 (8.6.0) × 3 个 build
  → The connection cannot be established because database version does not match driver version
    驱动自己带版本校验，连都不让连

V9 驱动 (9.0.0) × 9 个 build
  → 连得上，但同样只认 pg_catalog
```

所以**没有任何可获得的驱动能读 V8R3 的元数据**。

### 那就自己接管元数据

用 V9 驱动建立连接，元数据整套改由 `KingbaseLegacy` 自己发 SQL 取。
先把「一个客户端至少要问出来的东西」逐条实测了一遍，15 条全部可用：
模式、表、列（含精度与小数位）、主键、索引、外键、表与列注释、行数、
视图定义、序列及属性、触发器、例程、分页。

还有一处：**任何带参数的语句都会失败**——包括纯文本列、包括显式 CAST：

```
ERROR: cache lookup failed for type 536871955
```

不是类型推断的问题，是扩展协议本身：驱动在 Parse 消息里发的参数类型 OID
这个服务端不认识。`preferQueryMode=simple` 让参数在客户端拼进语句，
服务端不必解析类型 OID。实测通过，而且**精度不受影响**——
`123456789012.345678` 原样往返。

驱动的 `ResultSetMetaData` 也有四个方法会整条炸掉（`getTableName`、
`getColumnTypeName`、`isNullable`、`isAutoIncrement`），它们要去查 `pg_class`。
这四个**没有一个是读数据必需的**：类型号足以决定读取路径，也就决定了精度。
所以它们失败时退回保守默认值让查询继续，而不是让整条 SELECT 报一句
和用户的 SQL 毫无关系的错。代价是 SQL 编辑器里的查询结果不可编辑
（定位不到来源表），**表页不受影响**——那条路知道自己打开的是哪张表。

还有第三处，是**列类型冷门时**才露出来的：驱动手里静态认得的只有几十个常见类型，
碰上不认得的（比如 `regrole`），它要查 `pg_type` 才能回答 `getColumnType`——
于是一条普通的 `SELECT * FROM 某视图 LIMIT 200` 报
`relation "PG_CATALOG.PG_type" does not exist`，而报错里贴的是用户自己那条 SELECT，
看上去像 SQL 写错了。

第一版的保护漏了这一个，因为当时是拿 INT / NUMERIC / VARCHAR 的表测的——
那几种驱动静态就认得，**根本不会去查系统目录**。要挑一张列类型冷门的表才试得出来，
这一条已经补进 `tools/KingbaseProbe.java`。

这些方法没有一个是**读数据**必需的：拿不到类型号就按文本读，
而按文本读正是本项目在精度上最保守的那条路。所以失败时退回保守默认值让查询继续，
而不是让整条 SELECT 报一句和用户的 SQL 毫无关系的错。

### 顺带修掉一个不限于金仓的问题

生成测试数据时，`regclass` 列拿到了本工具编出来的 `partrel-1-bb`，
服务端一 CAST 就报 `relation "partrel-1-bb" does not exist`。

根因在生成器：类别 `OTHER`（认不出来的类型）和 `STRING` 合在同一条分支上，
于是按字符串列的规则造了个值。`uuid`、枚举、`inet`、`xml`、数组都一样——
**这些类型各有各的格式，随手编的文本几乎一定非法**。也就是说，
任何带 uuid 或枚举列的表，以前都灌不进测试数据，和是不是金仓无关。

改成认不出来就说认不出来：那一列跳过，交给数据库的默认值；
非空又没有默认值时当场说清楚是哪一列、为什么、怎么办，
而不是把数据库那句 not-null 违反甩给用户。用户自己指定的固定值照用——那是逃生口。

指路要指到**具体哪一格**。「给它选固定值」听着清楚，真去界面上找会发现有两列
都和固定值有关：一列是下拉的「生成方式」，另一列才是填值的「固定值」。
所以消息里两个列名逐字点出来，和对话框上的表头对得上。

三处都只在**确实撞上 V8R3** 时才启用，判据是症状（`pg_catalog` 查不到、
`sys_catalog` 查得到）而不是版本号：版本号格式各版本都在变，
比错了会把好连接拦下来。别的库一行代码都不会走到那条路上。

**实测结果**（`tools/KingbaseProbe.java`，走产品自己的 API，25 项全过）：
模式列表、系统模式过滤、表/视图/序列识别、表注释、列注释、主键、自增、
NUMERIC 精度与小数位、VARCHAR 长度、非空、类型分类、索引、
复合索引的列序、精度往返、参数化写回。

`PrecisionCheck` 在 KingbaseES V008R003C002B0320 上 **20 通过 / 0 不通过 /
3 项厂商行为**，达到准入门槛，`DbType.KINGBASE` 的「未验证」已摘掉。
V8R6 与 V9 的服务端手上没有实例，没验过——它们走常规路径，
理应正常，但「理应」不是验过。

OceanBase 仍然没有实例。

---

## SQL 编辑器的两件事

### 个位数行号会把那一行推歪

用户报的现象是「第一行 INSERT 和下面几行没对齐，在它上面回车一下就好了」。
实测下来是行号栏：

```
改动前   行号字体 System Regular 11.0   空格宽 3.25   数字宽 6.45
         行号 1-9   格宽 38.31   文字起点 38.31
         行号 10-14 格宽 41.50   文字起点 41.50
```

行号是右对齐补空格的（40 行的文档里第 9 行是 `" 9"`）。等宽字体下空格和数字一样宽，
各行的行号格因此一样宽；而这里的字体是**比例字体**，空格只有数字的一半——
个位数的那些行窄 3.2 像素，正文跟着往左挪 3.2 像素。在第 9 行上面回车，
那一行变成第 10 行，两位数不再需要补空格，于是「自己好了」。

字体为什么不对：CSS 里那句 `-fx-font-family: "Cascadia Mono", Consolas, monospace`
**一个族名都没解析上**，JavaFX 退回 System，而字号 11px 照样生效。
全程没有任何报错——字体族写错了不会抛异常，只会安静地换一个，
而换上来的那个恰好不等宽。

改成在代码里挑一个**确认装着的**等宽族（挑不到就用 JavaFX 的逻辑字体 `Monospaced`，
它一定存在也一定等宽），CSS 那边只留颜色和内边距。见 `LineNumbers`。

### 长 SQL 拖横向滚动条卡顿

`tools/SqlEditorProbe.java` 量的是真实帧间隔（用 `AnimationTimer`，不自己调
`layout()`——那量的是「完整重排一次多久」，会高估）：

| 一行长度 | 着色跨度 | 横向滚动 | 整篇只给一段样式 |
| --- | --- | --- | --- |
| 1014 字符 | 209 段/行 | 68–120 毫秒/帧 | 61–97 毫秒/帧 |
| 4134 字符 | 809 段/行 | 118–139 毫秒/帧 | 58–66 毫秒/帧 |

**结论和直觉相反**：1000 字符一行时，语法着色只占其中几毫秒，
九成开销在别处——RichTextFX 把**整行**交给 TextFlow 排版，哪怕屏幕上只看得见
一百来个字符；行有多长就排多长，横向滚动每一帧都要重来。那一层在组件内部，改不动。
着色要到 4000 字符一行才成为大头。

能绕开它的是**自动换行**：排版量被视口框住，

```
自动换行 + 纵向滚动   9–12 毫秒/帧，而且不随行长变化（4134 字符一行时仍是 10 毫秒）
```

所以加了一个「自动换行」开关（记住选择），不默认打开——换行会打乱缩进，
写 SQL 时很多人靠缩进看结构。

探针对横向滚动那一项**不设断言**，只如实记录：大头在组件内部，
摆一条永远红的断言只会变成噪音，还会盖住真正回归的那一天。断言留给能管的两条：
换行必须比横向快数倍，以及行号格宽度一致。绝对毫秒数也不断言——
同一份文本在不同负载下测出过 10 到 20 毫秒，拿它当断言只会得到一条时灵时不灵的红线。

---

## Oracle 与达梦的精度实测

`PrecisionConformanceTest` 跑在 H2 上，CI 里随时能跑，但它证明不了 Oracle 和达梦——
那两家的驱动、类型映射、绑定方式都不一样。所以把同一套用例抽成
`PrecisionCheck`（`plainly-driver-jdbc` 的 `diag` 包），拿到真实实例上跑：

```bat
java -Dfile.encoding=UTF-8 -cp "plainly-app\target\plainly.jar;plainly-app\target\deps\*" ^
     tools\PrecisionProbe.java "连接名" 模式名 [类型]
```

它会建一张 `PLAINLY_PRECISION_PROBE`、插四行、做一次参数化写回，**跑完删掉**。

### 结论

| | 达梦 DM8 8.1.2 | Oracle 23ai |
|---|---|---|
| BIGINT 上下界、2^53+1 | 通过 | 通过 |
| 30 位有效数字、负满位值 | 通过 | 通过（见下方「末尾零」） |
| 极小值保留 scale、不用科学计数法 | 通过 | 通过 |
| NULL 与空串区分 | 通过 | 厂商行为：Oracle 把空串存成 NULL |
| 精确数值归类、主键识别、只读降级 | 通过 | 见下方「驱动不报表名」 |
| 参数化写回往返 | 通过 | 通过 |
| 合计 | 22 通过 · 0 不通过 | 18 通过 · 0 不通过 · 5 厂商行为 |

**两家的数值精度都没有问题。** 下面两条是实测撞出来的、必须说清楚的差异。

### 一、Oracle 的 NUMBER 不保留末尾零

写进 `12345678901234567890.1234567890`，读出来是 `12345678901234567890.123456789`。

这**不是**读取路径削掉的。拿 `TO_CHAR` 直接问数据库，它自己给的也是后者；
而列确实声明成 `NUMBER(38,10)`（`DATA_SCALE=10`）。也就是说 Oracle 的 NUMBER
存的是**数值**，不是补零后的格式。

两个文本代表同一个数，**一位有效数字都没丢**。所以自检把它记成「厂商行为」而不是
不通过——报成缺陷等于让人去修一个修不掉也不该修的东西。

代价只有一处：网格判断「改没改过」用的是字符串比较，所以在 Oracle 上把
`...789` 手工敲成 `...7890` 会被当成一次修改，发出一条把值设成同一个数的 UPDATE。
无害，但值得知道。

**刻意没有在读取路径上补零。** 那等于在唯一的转换点上加一次格式化——
而这个项目全部的精度保证就建立在「那里不做任何转换」上面。

### 二、Oracle 的驱动不报结果集的来源表

`ResultSetMetaData.getTableName()` 对每一列都返回空串。它不支持这个，
因为报表名要额外的网络往返。

后果原来很严重：**Oracle 上每一张表的数据网格都是只读的**，而给出的理由是
「结果里有不属于任何表的列（表达式、函数或聚合）」——一句完全对不上号的话，
用户会去检查自己的查询，而问题根本不在那儿。

修法分两半：

- `SqlDialect.reportsResultSetTableNames()` 让每家如实声明。驱动不报表名时，
  只读理由改成说得通的那一句，并指出「从左侧打开表来改数据」；
- **表页知道自己打开的是哪张表**，所以由 `DbSession.attributeToTable` 把表名和主键
  补回结果集。自检里有一条专门验这条路（「补上表名后可编辑」），Oracle 上通过。

裸 SQL 查询在 Oracle 上仍然只读——那是诚实的：谁也不知道一条任意的 SELECT 该写回哪张表。

**达梦虽然继承 Oracle 的方言，但它的驱动是报表名的**，所以它覆写回 `true`。
不覆写的话会跟着继承一条它没有的限制，白白把达梦的网格变成只读——
这正是「能力要一家一家如实声明」的价值。

---

## 已知边界

按重要性排序，都是有意留到下一版的，不是遗漏：

1. **已有字段不能调整物理顺序。** 上移/下移只对新增字段生效——
   已存在的列要改物理顺序，MySQL 需要 `MODIFY ... AFTER` 重写整列，
   PostgreSQL 则根本做不到（要重建表）。与其做一半，不如只支持能做对的部分。

2. **SQLite 改不了列定义。** 这是 SQLite 自身的限制，需要「建新表→拷数据→删旧表→改名」
   四步重建，会丢索引和触发器。设计器会在预览里标红并禁用「应用」，
   而不是生成一条注定失败的 SQL。

3. **网格里的结果集是一次性读完的。** 这是有意的：网格只显示一屏，
   取回两百万行没有意义，靠「限制行数」兜底（默认 1000）。

   **导出不受这个限制。** 导出表走分页扫描；导出查询结果时选「重新执行，导出全部」
   会把语句重跑一遍、开游标逐行写出，内存占用与结果集大小无关——
   代价是语句再跑一次，且两次之间数据可能已经变了，界面上写明了这一点。

   验证方式见 `tools/StreamExportProbe.java` 与 `tools/MysqlStreamProbe.java`：
   在 `-Xmx256m` 下先用全量取法跑同一个结果集（必须 OOM，否则用例没造够大、
   结论不作数），再走流式（必须跑完）。**这条只能用内存验，不能看代码**——
   MySQL 给正数 `fetchSize` 照样把整个结果集拉进客户端内存，
   PostgreSQL 不关自动提交也一样，两者都不报错。

4. **达梦的触发器视图和 Oracle 不一样，是在真实实例上撞出来的。**
   DM 继承 Oracle 的方言，但 `ALL_TRIGGERS` 里那一列叫 `TRIGGERING_TYPE` 而不是
   `TRIGGER_TYPE`，`TRIGGER_BODY` 存的也是**整条** `CREATE OR REPLACE TRIGGER` 语句
   而不只是触发器体。两条都已按 DM 8.1.2 实例修正并有测试守着（`TriggerDdlTest`）。

   真正的教训不在这两列上，而在**它是怎么暴露的**：`listTriggers` 原来把查询异常
   吞掉返回空表，于是「读不到」和「这张表没有触发器」在界面上长得一模一样——
   用户建完触发器回到列表看到空的，只能怀疑是创建失败了。现在触发器页会说清楚
   到底是哪一种，读到 0 条时还会把**实际发出去的那条语句**摆出来，
   让「没有触发器」这个结论可核对。

5. **SQL Server 的精度未在真实实例上验证。** 方言层已经接好，但没有实例跑过精度自检，
   所以界面上的类型名如实标着「未验证精度」，而不是假装已经支持。

   Oracle 和达梦<b>已经验过了</b>，结论见下面「Oracle 与达梦的精度实测」。

6. **SQLite 的建表语句写不出自增列。** 它的自增只能写成
   `INTEGER PRIMARY KEY AUTOINCREMENT`，必须在列定义里、类型必须正好是 INTEGER、
   而且不能再单独声明 `PRIMARY KEY (...)` 子句——本工具生成建表语句的方式恰是后者。
   界面上会明说这一项得自己补，而不是发一条注定失败的 DDL。

   Oracle 用 `GENERATED BY DEFAULT AS IDENTITY`（12.1 起），达梦用 `IDENTITY(1, 1)`。
   前者在 `OracleSyntaxSmokeTest` 里真执行过（建表 + 不给主键值插 + 显式值插）；
   后者依据 DM 手册，没有实例可验。**自增的计数器种子仍要自己调**：
   Oracle 改种子要 12.2 的 `START WITH LIMIT VALUE`，验证不了就不发那条语句，
   复制表时界面会提示这一句。

7. **复制表不带外键、触发器和表级注释。** 外键指向的表未必也复制了，
   触发器复制过去会对着新表继续写日志——多半不是用户想要的。对话框上写明了。

8. **JSON 导入要读两遍文件。** 一遍收集字段名的并集，一遍读数据。
   只看第一个对象定表头的话，后面对象多出来的字段会被**整列安静丢掉**，
   那比多读一遍文件糟糕得多。

9. **虚拟外键没有任何强制力。** 它是本机的标注，目标库上不会因此多出约束——
   标了 `orders.user_id → users.id`，插一条 user_id 不存在的订单照样插得进去。
   外键栏里专门有一列写着「本机标注 · 无约束」，把它和数据库真有的约束分开。

10. **用户与权限只能看，不能改。** 「谁能动这张表」是排查问题时的高频需求，所以做了查看；
   但 MySQL 8 的角色、PostgreSQL 的 role 与默认权限、Oracle 的 profile 三家模型差得很远，
   而这是**发错一条就可能让生产库上的应用连不上**的地方。没有真实实例验证过的 `GRANT`
   不该发出去，所以这一版明说只读。

11. **结束会话只支持 MySQL 和 PostgreSQL。** 其余几家的语法和所需权限没有实例验证过，
    界面上如实灰掉并写明原因。PostgreSQL 上额外给了「只取消当前语句、保留会话」——
    它的 `pg_cancel_backend` 和 `pg_terminate_backend` 本来就是并列的两件事。

12. **暗色主题下，连接的标记色不跟着变。** 红=生产、绿=测试这套颜色是**语义**，
    不是装饰。跟着主题调色，等于把「这是生产库」这个信号也一起改了。

13. **安装包没有代码签名，三个平台都没有。** 包本身已经能出（见「打包」）：
   Windows 的 SmartScreen 会拦一次（「更多信息 → 仍要运行」），macOS 的
   Gatekeeper 也会（「系统设置 → 隐私与安全性 → 仍要打开」），Linux 的
   deb/rpm 没有 GPG 签名。签名需要证书或私钥（Windows 是 Azure Trusted Signing
   按月付费或别家的 EV 证书，macOS 是 Apple Developer ID），拿不到就签不了，
   这一条绕不过去。Release 里附了 `SHA256SUMS.txt`，但那证明的是
   「文件没在传输中损坏」，**不是**「文件确实出自发布方」——两件事不能混。

14. **Linux 与 macOS 上，保存的口令没有真正加密。** Windows 走 DPAPI，
   另外两个平台目前只做了可逆编码，还没接 libsecret / 钥匙串。
   应用启动时状态栏会如实写出当前用的是哪一种，界面上不假装安全。
   不希望口令落盘就勾掉「保存密码」，那条连接的口令只在内存里。

15. **macOS 一次只能打一种架构的包。** Intel 和 Apple Silicon 的包互不通用，
   而 jpackage 只能给它跑在的那台机器打包。CI 里用 `macos-13`（Intel）和
   `macos-14`（Apple Silicon）两个 runner 各打一份，文件名上带着架构区分开——
   两份名字一样的话，用户下载了也分不出哪个是自己要的。

16. **macOS 的包还没有在真机上装过。** 打包流程是照苹果的规则写的并且在 CI 上
   能跑通，但「装完能不能正常启动」没有实机验证过。按这个项目的规矩，
   没在真机验过的就如实标着未验证，不含糊过去。

---

## 打包

三个平台各有一个脚本。**jpackage 不能交叉编译**——它打出来的包里装着本平台的
运行时镜像和本平台的启动器二进制，所以 Windows 上只能出 exe/msi，Linux 上只能出
deb/rpm，macOS 上只能出 dmg/pkg。一台机器出不齐三个平台，这一条绕不过去；
要一次出齐就得有三台机器，`.github/workflows/release.yml` 就是替代那三台机器的。

**Windows**

```bat
REM 出一个可以直接双击运行的目录（不需要任何额外工具）
powershell -ExecutionPolicy Bypass -File package.ps1

REM 再出 MSI 安装包
powershell -ExecutionPolicy Bypass -File package.ps1 -Msi

REM 本机没有 WiX 时，让脚本自己取一份免安装的
powershell -ExecutionPolicy Bypass -File package.ps1 -Msi -FetchWix

REM 装到当前用户，整个过程不弹 UAC
powershell -ExecutionPolicy Bypass -File package.ps1 -Msi -PerUser
```

**Linux 与 macOS**

```bash
./package.sh                        # 只出 app-image
./package.sh --installer            # Linux 默认出 deb，macOS 出 dmg
./package.sh --installer --type rpm # RHEL / Fedora
./package.sh --installer --type pkg # macOS 的另一种
```

产物都在 `dist/`。Linux 上打 rpm 需要 `rpmbuild`（`apt install rpm`）。

**平台包不能拿错。** `javafx-controls` 这几个带 classifier 的依赖里装的是本地库——
Windows 是 dll、Linux 是 so、macOS 是 dylib。拿错了**编译和打包全程不报错**，
要等界面真正起来那一刻才 `UnsatisfiedLinkError`。`plainly-app/pom.xml` 里按操作系统
和架构自动选（macOS 还要分 Intel 的 `mac` 和 Apple Silicon 的 `mac-aarch64`），
`package.sh` 在调 jpackage 前还会再查一遍 `deps/` 里到底是哪个平台的包。

**macOS 的版本号必须从 1 开头。** 苹果要求 `CFBundleShortVersionString` 的第一段
是正整数，所以 `0.1.0` 在 Windows 和 Linux 上都合法，到 macOS 上 jpackage 直接拒绝。
`package.sh` 会提前查出来并说明两条出路，而不是让 jpackage 抛一句
「版本号无效」——那句话不会告诉你这是平台差异。

**运行时模块清单只有一份**：`tools/jpackage-modules.txt`，三个脚本共用。
原来它硬写在 `package.ps1` 里，加上另外两个平台之后就会变成三份，
而三份不同步的后果和「两处版本号」一模一样：不报错，只是某个平台的包缺模块。

**MSI 需要 WiX Toolset 3.x**——jpackage 在 Windows 上就是调它的 `candle.exe` /
`light.exe` 生成安装包的。脚本按 PATH → `build\wix` 的顺序找，`-FetchWix`
会去官方仓库取一份免安装的二进制包解到 `build\wix`，不动系统、不需要管理员。

### 三处容易出事、而且不会报错的地方

1. **运行时里要带哪些模块，得自己说。** jpackage 靠 jdeps 静态分析决定裁哪些模块，
   而下面几个全是**反射才用到**的，分析看不见：`jdk.crypto.ec`（少了它，
   连 MySQL / PostgreSQL 的 TLS 握手挑不出算法套件）、`jdk.charsets`（少了它，
   `Charset.forName("GBK")` 抛异常，导入导出选 GBK 就炸）、`jdk.localedata`
   （少了它，中文环境下日期数字格式退回英文）。三样漏掉都不会在打包时报错，
   只会在用户机器上以看不出原因的方式失败。清单在 `tools/jpackage-modules.txt`
   （三个平台的打包脚本共用这一份），验证方式见 `tools/RuntimeCheck.java`——
   它拿打包后的模块集真去做这几件事。

2. **图标是生成的，不是仓库里另放一张。** 窗口图标由 `Icons.appIcons()` 在运行时画出来，
   安装包那张由 `tools/MakeAppIcon.java` 从同一处导出成 `.ico`。另放一张就意味着
   改了代码之后开始菜单里的图标不会跟着变，而且没有任何提示。

3. **`--win-upgrade-uuid` 那个 GUID 永远不要改。** MSI 靠它认出「这是同一个产品的新版本」。
   改掉之后新版本不再升级旧版本，而是并排装两份——两个开始菜单项、两个卸载入口。

4. **`.ps1` 必须存成带 BOM 的 UTF-8。** PowerShell 5.1 读没有 BOM 的脚本时
   按系统 ANSI 编码来，脚本里的中文全变乱码，然后以一堆莫名其妙的语法错误收场。
   这一条会响，但报出来的位置和真正的原因毫无关系。

### 打完之后怎么验

验证不能只看「jpackage 退出码是 0」：

```bat
REM 把 MSI 的载荷解出来看（不安装、不写注册表）
msiexec /a dist\Plainly-0.1.0.msi /qn TARGETDIR=<某个空目录>
```

然后跑起来看主窗口。**注意 jpackage 的 GUI 启动器会把自己重启成一个子进程**，
JVM 和窗口在**子进程**里——只盯着父进程会看到「4 个线程、6 MB、没有窗口」，
从而误判成启动失败。

---

## 发新版本

### 一处改版本号

版本号只有一个来源：**根 `pom.xml` 的 `<version>`**。`package.ps1` 从那里读，
`-SNAPSHOT` 后缀自动去掉。

```xml
<version>0.2.0-SNAPSHOT</version>   <!-- 打出来就是 0.2.0 -->
```

原来脚本里另写了一份 `$version = '0.1.0'`。两处一旦不同步，后果不是报错而是
**升级悄悄没发生**：pom 改了、脚本忘了改，出来的 MSI 仍自称旧版本号，
装到已有同版本的机器上，Windows 认为「这是同一个版本」，既不升级也不提示——
用户双击、看到进度条、看到「完成」，打开的还是旧的。

`-Version 0.2.0` 可以临时覆盖，用来做升级演练。

### MSI 对版本号的硬性限制

只比较**前三段**，每段都是纯数字，且有上限：主 ≤ 255、次 ≤ 255、构建 ≤ 65535。

- `1.0.0-rc1`、`1.0.0+build3` —— jpackage 直接拒绝；
- `1.0.0.4` —— 第四段被**无视**，`1.0.0.4` 和 `1.0.0.5` 在 Windows 眼里是同一个版本。
  这一条最危险，因为它不报错。

脚本在打包前就把这几条查了，不合规当场停下。

### 升级是怎么发生的

同一个 `UpgradeCode` + 更大的 `ProductVersion` = **主版本升级**：
Windows 在一次事务里卸掉旧版、装上新版，「程序和功能」里始终只有一条。

用户不需要先卸载。直接双击新的 MSI 就行。

**发布前对一遍**（只读 MSI 文件，不安装）：

```bat
powershell -ExecutionPolicy Bypass -File tools\MsiUpgradeCheck.ps1 上一版.msi 这一版.msi
```

它查五件事，每一件不满足都不会在安装时报错，只会让用户装完还在用旧版本：
UpgradeCode 是否相同、ProductCode 是否不同、版本是否真的变大、
旧版本是否落在新包声明的可升级区间里、有没有挡降级的规则。

### 用户的数据不会丢

连接、口令、查询历史、收藏、标签页现场都在 `%APPDATA%\Plainly\plainly.db`，
**在安装目录之外**，所以「卸旧装新」碰不到它。

因此库结构的变更必须是**只往前加**的：`LocalStore.migrate()` 里一律用
`CREATE TABLE IF NOT EXISTS` 和 `addColumnIfMissing`，绝不改列、不删列、不重建表。
新版本会遇到任意老版本留下的库文件，而那份库里装着用户攒了很久的东西。

### 检查更新：只提示，不下载，也不执行

菜单「工具 → 检查更新…」里配一个发布地址（`owner/repo`，或者整条
`https://github.com/owner/repo` 粘进去也认），应用就能查 GitHub 的
`releases/latest`，比出有没有更新的一版。

查到了只做两件事：在工具栏下面挂一条提示，以及在用户点「去下载」时打开发布页。
**不下载安装包，也不运行任何东西。**

这条线是照着原来那段「没有自动更新」的顾虑划的——当时列的三个前提里，
前两个（一个放版本信息的地址、一份能校验的下载）现在有了，
第三个**代码签名证书**仍然没有。而没有签名时，替用户在无人值守的情况下跑起一个
SmartScreen / Gatekeeper 会拦的安装程序，比让他自己去下载更糟。所以停在提示。

三件事按这个项目的惯例明说：

- **出厂是关的，地址留空，此时一个网络请求都不发。** 不是发出去再把结果丢掉，
  是根本不走到网络那一步——README 上面「安全」一节承诺过这个工具唯一的网络行为
  是连你自己配的库，多一个功能不该让那句话悄悄作废；
- 打开之后，每次启动会向 GitHub 请求一次版本信息，对方因此能看到这台机器的 IP。
  设置页上就是这么写的；
- 查不成不弹框，只往状态栏写一句。但**不会完全不说**——这个项目吃过那个亏
  （`listTriggers` 把异常吞掉返回空表，于是「读不到」和「本来就没有」长得一模一样）。
  地址填错或者连不上 GitHub 的话，全程静默会让用户一直以为「一直没有新版本」。

#### 版本比较只比前三段，这是照抄 Windows 的行为

`0.1.0.4` 和 `0.1.0.5` 在 Windows 眼里是**同一个版本**（MSI 只比前三段）。
所以更新检查也只比前三段——按四段比的话会造出这样一条路：提示「有新版本」→
用户下载 → 双击 → 进度条 → 「完成」→ 打开还是旧的，全程不报错。

这和上面「一处改版本号」记的是同一个陷阱，更新检查是它的第二个入口。
`VersionTest.ignoresFourthSegmentLikeWindowsDoes` 和
`UpdateCheckerTest.fourthSegmentIsNotAnUpdate` 两条用例守着它，
并且都做过证伪（把比较改成四段，它们确实变红）。

从源码运行时 jar 没有 manifest，取不到版本号，这时候如实说「没法比较」，
不拿 `0.0.0` 去凑——凑的话，开发版每次启动都会被告知有新版本，
而那台机器上根本没有安装包可装。

---

## 关于 Maven 配置

本机全局 `~/.m2/settings.xml` 把 aliyun 配成了 `mirrorOf=*`，
但该地址在这台机器上不可达（`curl` 返回 000），
表现为所有 Maven 命令挂起到超时。同时 `D:\apache-maven-3.6.0\conf\settings.xml`
里还有个不可达的 `nexus-aliyun` 仓库，而 `-s` 不覆盖全局配置。

仓库里给的是 `settings.xml.example`，复制成 `settings.xml` 再用。
**真实的 `settings.xml` 不进版本库**：它可能带着 `<localRepository>`——
那是各人机器上的路径，推上去会让别人的构建指向一个不存在的目录，
而 Maven 不会报错，它会安静地把那个目录当成空仓库重新下载一遍。

本机已有依赖缓存的话，在自己的 `settings.xml` 里写上 `<localRepository>`
就能完全离线构建（加 `-o`）。

**两个参数都要带**：

```bat
mvn -s settings.xml -gs settings.xml <goal>
```

JavaFX 的平台包（`win` classifier）体积较大，首次拉取较慢；
无 classifier 的主 jar 只有 302 字节，是个空壳，必须用 `win` 那份。

---

## 技术选型的由来

- **JavaFX + JDBC**：JDBC 的 `BigDecimal` 是语言层的精确十进制类型，
  且每家数据库厂商都提供 JDBC 驱动 —— 精度和覆盖面这两项上它是最强的。
- **不引 AtlantaFX**：界面按设计原型手写 CSS，比套主题更贴近既定视觉。
- **classpath 而非 module-path 启动**：`Launcher` 不继承 `Application`，
  绕开「JavaFX runtime components are missing」，代价是一个五行的类。
- **RichTextFX 只用它的语法高亮**：补全弹窗、别名解析、Tab 补全都是自建的。
  这正是选 JavaFX 相对 Monaco 要付出的主要成本，也是后续工作量的大头。

---

## 参与开发

见 [CONTRIBUTING.md](CONTRIBUTING.md)。其中有一条是这个项目特有的：
涉及某个数据库的真实行为时，**要写探针在真实实例上验证**，
而且探针本身得先证明它拦得住那个 bug。

这个项目里最贵的错误全都是「读代码推断出的结论」和「真实行为」不一致造成的，
而且它们都不报错——Oracle 的 identity 子句顺序、达梦的触发器视图列名、
MongoDB 把 `"0123"` 存成 `123`，每一条都是探针跑出来的，
读代码、写单元测试都发现不了。

---

## 安全

**这个工具会接触你的数据库凭据。** 相关设计如下，看完再决定要不要用：

- 密码存在本机配置库里（Windows 是 `%APPDATA%\Plainly\plainly.db`，
  Linux / macOS 是 `~/.plainly/plainly.db`）。**保护程度按平台不同，这一点必须说清楚：**

  | 平台 | 保存的口令怎么存 |
  |---|---|
  | Windows | **DPAPI** 加密，绑定当前用户与当前机器。换机器、换用户都解不开 |
  | Linux / macOS | **只做了可逆编码，等于没加密**。还没接 libsecret / 钥匙串 |

  非 Windows 上那一栏不是疏漏，是还没做。应用启动时状态栏会如实写出当前的存储方式
  （「未加密（当前平台无可用的系统凭据保护）」），不希望口令落盘就勾掉「保存密码」。

  之所以不在那儿随便塞个 AES 顶上：密钥必须跟着程序走，等于把锁和钥匙放在一起，
  那是「看起来加密了」，比明说未加密更危险；
- 「导出连接」功能生成的是**便携格式**，用 PBKDF2WithHmacSHA256 + AES/GCM，
  口令由你在导出时给。这份文件离开了 DPAPI 的保护，请当作敏感文件对待；
- 勾掉「保存密码」的连接只在内存里持有口令，不落盘；
- 本工具**不做任何遥测**，不联网上报，也没有内置的更新检查。
  唯一的网络行为就是连你自己配的数据库。

发现安全问题请**私下**告知（GitHub Security Advisory 或私信），
不要直接开公开 issue——在修好之前，公开的漏洞描述会让所有使用者暴露。

---

## 许可证

[Apache License 2.0](LICENSE)。

本仓库的源码不含任何第三方代码，但 `package.ps1` 生成的安装包会把依赖一起分发，
各依赖的许可见 [NOTICE](NOTICE)。其中两条值得留意：

- **MySQL 驱动是 GPLv2 + Universal FOSS Exception。** 本项目以 Apache-2.0 开源，
  在那份豁免的认可名单上，因此没有障碍；但若有人把它改成**闭源产品**分发，
  豁免不再适用，需要另行取得 MySQL 商业授权，或者去掉该驱动。
- **OpenJFX 是 GPLv2 with Classpath Exception。** Classpath Exception 允许独立模块
  与之链接并按自己的许可分发，不会因此变成 GPL。
