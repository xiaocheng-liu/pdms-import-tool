# PDMS 数据导入工具

跨平台（Windows / macOS / Linux）桌面工具：把某个文件夹下的 CSV 文件（每个 CSV 对应一张表）**并发导入**到 Oracle、PostgreSQL、达梦、人大金仓 KingbaseES 或 MySQL 数据库。

核心能力：
1. **选择数据库**：Oracle / PostgreSQL / 达梦 / 人大金仓 KingbaseES（PG 兼容模式）/ MySQL，填写连接信息并可测试连接
2. **选择文件夹**：自动扫描目录下所有 `.csv`，表格化勾选要导入的表
3. **并发导入**：多表并行 + 超过阈值的大表自动按字节分片并行写入

---

## 一、运行环境

- JDK 21 及以上（**最低 21**，低于 21 会拒绝启动），无需安装其它软件
- 目标数据库需能通过网络访问

## 二、启动方式

### Windows

1. 安装 JDK 21 及以上（建议设置环境变量 `JAVA_HOME`，并把 `%JAVA_HOME%\bin` 加入 `PATH`）
2. 双击 `run.bat`：直接用 PATH 中的 `java` 启动，启动前会校验版本，低于 21 时提示并退出

Windows 端 `run.bat` 不再做 JDK 查找，也不读取 `jdk-path.txt`；机器上装了多个 JDK 时，请把 JDK 21 的 `bin` 目录放到 `PATH` 最前面。

### macOS / Linux

```bash
./run.sh                # 自动查找 JDK（要求 21 及以上）
./run.sh /opt/jdk-21    # 指定 JDK 安装目录
```

`run.sh` 仍按优先级查找 JDK：命令行参数 > `PDMS_JAVA_HOME` > `jdk-path.txt` > `JAVA_HOME` > PATH 中的 `java`。

### 手动运行

```bash
mvn -o package
java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Xmx1g -jar target/pdms-import-tool.jar
```

## 三、使用步骤

1. **数据库连接**：选择数据库类型（Oracle / PostgreSQL / 达梦），填写主机、端口、服务名（或库名）、用户名、密码、Schema（默认 `KBE`），点击"测试连接"
   - Oracle：`jdbc:oracle:thin:@//host:port/服务名`；若使用 SID，服务名填写 `SID:实例名`
   - PostgreSQL：`jdbc:postgresql://host:port/库名`，自动附加 `currentSchema`
   - 达梦：`jdbc:dm://host:port/库名?schema=大写SCHEMA`
   - 人大金仓 KingbaseES：`jdbc:kingbase8://host:port/库名?currentSchema=小写SCHEMA`，默认端口 54321（PG 兼容模式：表名/列名转小写 + 双引号）
   - MySQL：`jdbc:mysql://host:port/库名?...`（自动附加 `allowLoadLocalInfile=true`、`rewriteBatchedStatements=true` 等参数），默认端口 3306；标识符转小写 + 反引号引用。MySQL 的库即 schema，**Schema 一栏留空**（界面选择 MySQL 时该栏会自动置灰）
2. **数据源文件夹**：点击"选择文件夹"指定 CSV 目录，点"扫描 CSV"（默认已填入常见导出目录）
   - 只识别 `.csv` 文件，文件名即表名（如 `rule_dosage.csv` → 表 `RULE_DOSAGE`）
   - Oracle/达梦表名转大写，PostgreSQL 转小写
3. **勾选要导入的表**：支持全选 / 全不选 / 反选，按大小排序，大表会自动标记分片数
4. **导入选项**：
   - 并发线程数（1~32，默认 6，同时是最大数据库连接数）
   - 批量提交行数（默认 5000，建议 2000~10000，调大提升吞吐）
   - 大表分片阈值（默认 100MB，超过则拆分并行）
   - 单表最大分片数（默认 8，实际分片数 = min(并发线程数, 该值)）
   - 写入超时秒数（默认 120，填 0 关闭）：单个批次执行/提交超过该时间仍无进展时，自动取消当前批次
   - CSV 编码（UTF-8 / GBK）
   - 导入前清表：不清空 / TRUNCATE / DELETE（危险操作会二次确认）
   - **极速模式**（默认关闭）：PostgreSQL 走 `COPY` 批量加载，Oracle/达梦走 `INSERT /*+ APPEND */` 直接路径插入；失败自动降级为标准 INSERT，数据不丢
   - 表不存在时自动建表（按 CSV 采样推断列类型，执行前弹窗预览并可编辑）
     - PostgreSQL 建表前会自动 `CREATE SCHEMA IF NOT EXISTS`（schema 名转小写）；Oracle/达梦的 schema 即用户，不会自动创建
   - 空字符串转 NULL、失败跳过继续
5. 点击**开始导入**，下方日志与表格实时刷新进度；可随时点**停止**；结束后可**导出失败明细**

## 四、实现要点

- **流式处理**：commons-csv 流式解析 + JDBC 批处理提交，1GB 级 CSV 内存占用稳定在几十 MB
- **大表分片**：按字节区间切分，切分点保证落在行首且引号成对（不会切坏带换行的字段），每个分片独享连接与事务
- **方言抽象**：`Dialect` 收敛三种库的 URL、驱动、标识符大小写、清表语句、极速加载与类型映射
- **时间列绑定（重要）**：统一用 `setTimestamp` 配普通占位符 `?`，**不拼接 `TO_DATE(...)` / `TO_TIMESTAMP(?, '格式')`**。因为绑定值本身已是 `Timestamp`，再套日期函数会让 Oracle 先按会话 `NLS_TIMESTAMP_FORMAT`（默认形如 `28-SEP-26 09.59.47.000000000 AM`）隐式转成字符串，再按给定格式解析，从而报 `ORA-01858（在要求输入数字处找到非数字字符）`
- **值绑定与校验**：时间列宽容解析 `2026-09-28`、`2026-09-28 09:59:47`、`2026-09-28 09:59:47.123`、`2026/9/28`、`2026-9-8 9:5:3`、带 `T` 分隔等写法；数字列严格解析；**绑定阶段**发现格式非法时只记录该行并跳过，失败原因用中文写明列名与值（如「列 CREATE_TIME 的值「药道医疗」不是合法日期时间」），不会因个别坏行导致整表失败
- **类型推断**：采样前 500 行判断整数 / 小数 / 日期 / 日期时间 / 长文本 / 字符串，用于建表与参数绑定
- **失败行精确定位**：批量提交失败先回滚，再判断错误类型
  - 数据行问题（约束冲突、数值/长度超限等，SQLState 22/23/21 类）→ **逐行重放**该批：每行前打 `Savepoint`，坏行回滚后逐条记录，好行照常入库，因此**每个坏行都能精确记录**
  - 环境性问题（连接中断、表/列不存在、无权限等）→ 整批只记录 1 条，避免同一个错误刷出上千条明细
  - 明细字段：表名、文件行号、错误码（SQLState/错误码）、错误原因、**完整原始 CSV 行**（可直接修复后重放）
- **失败明细三重保障**：① 实时写入 `~/.pdms-import-tool/logs/failures_时间戳.tsv`（每条 flush，程序被强杀也不丢）；② 界面日志区红字回显前 300 条；③ 内存保留最多 2 万条，可点「导出失败明细」另存
- **极速模式**（可选，默认关闭）：批量加载通道比逐行 INSERT 快数倍
  - PostgreSQL：`COPY ... FROM STDIN WITH (FORMAT csv, HEADER, NULL)`，CSV 字节流交服务端解析；空串转 NULL 时 `NULL ''`，保留空串时 `NULL '\N'`；多分片可并行 COPY
  - Oracle/达梦：`INSERT /*+ APPEND */` 直接路径插入；因直接路径插入对表加排它锁，**自动降为单分片写入**
  - 不开启并行 DML（不执行 `ALTER SESSION ENABLE PARALLEL DML`）：并行 DML 会让每条语句启动 PX 从属进程并在提交时等待回收，在 `parallel_max_servers` 紧张或表并行度不合理时会造成长时间阻塞，收益不稳定
  - **失败自动降级**：COPY/APPEND 报错时回滚并从该分片起点改用标准 INSERT 重放（降级提示写入日志），已有提交数据的场景不会重复写入
- **写入超时与自动中断**（防止一直卡在"提交中"）：
  - 连接层：`ConnectionFactory` 统一设置 JDBC 网络读取超时（未配置写入超时时按 10 分钟兜底），Oracle 额外设置 `oracle.net.READ_TIMEOUT`，避免数据库端无响应时 JDBC 调用无限期挂起
  - 批次层：每批 `executeBatch` 前设置查询超时（等于「写入超时秒数」）
  - 看门狗：检测到单个批次执行/提交超过「写入超时秒数」仍无进展时，主动 `Statement.cancel()` 取消该批次
  - 取消/超时错误由 `SqlErrors.isCancelOrTimeout` 识别并**强制上抛**，不会被"失败跳过继续"当作普通坏行静默吞掉（否则整批丢失但整表仍显示成功）
  - 结果：极速模式下自动降级为标准 INSERT；标准模式下该表快速标记失败，日志给出 `[中断]/[超时]` 与具体原因
- **性能实测**（本机 1.13GB / 218 万行 CSV，仅客户端解析，不含数据库）：单线程 13.6 万行/s（70.6 MB/s），6 分片并行 28.8 万行/s（149.5 MB/s）——客户端不会成为瓶颈，实际速度取决于数据库写入能力

## 五、Windows 打包为 exe（可选）

在有 JDK 21+ 的 Windows 机器上，可用 `jpackage` 生成本地安装包（需先安装 [WiX 3.x](https://wixtoolset.org/) 才能生成 msi）：

```bat
mvn -o package
jpackage --type exe --name pdms-import-tool ^
  --input target --main-jar pdms-import-tool.jar ^
  --main-class com.moral.Launcher --java-options "-Dfile.encoding=UTF-8 -Xmx1g" ^
  --icon icons\PDMS.ico ^
  --win-console
```

macOS 生成 dmg：

```bash
jpackage --type dmg --name pdms-import-tool \
  --input target --main-jar pdms-import-tool.jar \
  --main-class com.moral.Launcher --java-options "-Dfile.encoding=UTF-8 -Xmx1g" \
  --icon icons/PDMS.icns
```

仅生成绿色 exe / app（不生成安装包）可用 `--type app-image`。

**图标说明**：jar 内已内置 `icons/pdms-*.png`，直接 `java -jar` 时窗口标题栏、任务栏 / Dock 会自动显示 PDMS 图标，无需额外配置；
上面的 `--icon` 仅用于 `jpackage` 生成本地安装包（exe / dmg）时设置文件图标。图标源文件在包内 `icons/` 目录：

| 文件 | 用途 |
| --- | --- |
| `icons/pdms-16~1024.png` | 运行时窗口 / 任务栏图标，同时是打包素材 |
| `icons/PDMS.icns` | macOS `.app` / dmg 图标 |
| `icons/PDMS.ico` | Windows exe 图标 |

## 六、常见问题

| 现象 | 处理 |
| --- | --- |
| 提示"未找到数据库驱动" | 使用 `mvn -o package` 打出的 fat jar（已内含三种驱动），不要直接用 IDE 单类运行 |
| Oracle 中文乱码 | CSV 编码选择与实际文件一致（一般 UTF-8）；本工具全程按 UTF-8 绑定参数 |
| 导入报 ORA-12899（字段太长） | 自动建表时采样行数有限，可先在数据库里手工建表再用本工具导入 |
| TRUNCATE 报外键错误 | 清表方式改为 DELETE，或先临时禁用外键约束 |
| PG 报 `schema "xxx" does not exist` | 勾选"自动建表"后本工具会先 `CREATE SCHEMA IF NOT EXISTS`；若报权限不足，请让 DBA 授权或在库中先手工建好 schema |
| 一张表准备失败后，后面所有表都报 `current transaction is aborted` | 已在准备阶段失败时回滚主控连接，不会再连坐；若仍遇到请确认使用的是最新 jar |
| 极速模式有什么限制 | PostgreSQL / 人大金仓用 COPY（金仓走驱动自带 CopyManager）；MySQL 用 LOAD DATA LOCAL INFILE，需对目标表有 INSERT 权限（不需要超级用户，因为走 STDIN 而非服务端文件）；Oracle/达梦用直接路径插入，期间该表被加排它锁、会降为单分片。若表上有触发器、或导入期间还有别的会话在写同一张表，请关闭极速模式。失败时日志会出现 `[降级]` 提示并自动改回标准模式 |
| 导入很慢 / 进度长时间不动 | 表格的"成功行/失败行"是**实时更新**的（每 2000 行刷新），先看是否在推进；若 10 秒以上无进展，日志区会自动输出**看门狗**信息：`[等待] 表名：正在等待数据库写入/提交，已 40 秒无进展；已成功 N 行，批次 X 次，最近一批 Z ms`。提示"等待数据库写入/提交"→ 瓶颈在库端（锁等待、触发器、大量索引、redo/磁盘）；提示"正在读取 CSV"→ 瓶颈在客户端/网络。每张表结束还会打印 `[统计] 批次 X 次，执行累计 A ms，提交累计 B ms`。点「停止」会立即中断挂起的数据库操作 |
| 一直卡在"提交中"不动 | 看门狗提示"正在等待数据库写入/提交"说明瓶颈在库端（锁等待、触发器、大量索引、redo/磁盘）。现在工具会在单批超过「写入超时秒数」（默认 120 秒）后自动取消该批次：极速模式自动降级为标准写入，标准模式标记失败并写明"写入超时/被中断"。想给慢库更多时间可调大该值；填 0 关闭自动中断（仍保留 10 分钟连接层网络超时兜底） |
| 导入速度还能怎么提 | ① 勾选极速模式；② 调大并发线程数与批量提交行数；③ 临时把索引/约束置为不可用、导入后重建；④ 确认目标库 redo/归档与磁盘 IO 不是瓶颈 |
| 有失败行，怎么定位 | 三条途径：日志区红字 `[失败行] 表名 第 N 行：错误码 原因`、实时落盘文件 `~/.pdms-import-tool/logs/failures_时间戳.tsv`、点「导出失败明细」另存为表格文件；表名后带 `[分片N/M]` 表示来自大表分片，行号为该分片内的序号 |
| Windows 提示文件被占用 | 关闭 Excel 等占用 CSV 的程序后重试 |
| 导入速度慢 | 调大并发线程数与批量提交行数；确认目标库 redo / 归档空间充足 |

## 七、目录结构

```
pdms-import-tool/
├── pom.xml                  # 依赖与 shade 打包配置（编译目标 Java 21）
├── run.sh / run.bat         # 启动脚本（Windows 端直接用 PATH 中的 java）
├── jdk-path.txt             # JDK 路径配置模板（仅 run.sh 读取）
├── icons/                   # 应用图标（pdms-16~1024.png / PDMS.icns / PDMS.ico）
└── src/main/java/com/moral/
    ├── Launcher.java        # 入口（启动时应用图标）
    ├── model/               # 配置、选项、进度、结果等模型
    ├── db/                  # 方言与连接工厂（Oracle / PG / 达梦 / 人大金仓 / MySQL）
    ├── csv/                 # 表头采样、流式读取、分片规划、类型推断、建表语句
    ├── task/                # 导入引擎、分片任务、批处理写入、失败记录
    ├── ui/                  # Swing 界面（FlatLaf 深色仪表盘）
    └── util/                # 配置持久化、跨平台适配、图标加载、格式化、EDT 工具
```

配置文件保存在用户主目录 `~/.pdms-import-tool/config.properties`（连接信息、导入选项、上次目录），密码以明文保存，仅适合本机自用场景。
