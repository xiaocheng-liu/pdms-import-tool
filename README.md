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
2. 双击 `run.bat`：优先用同目录 `jdk-path.txt` 指定的 JDK，未指定时用 PATH 中的 `java`；检查 Java 可用后，使用同一 JDK 的 `javaw.exe` 启动；启动成功后命令行窗口自动关闭，出错时保留提示（运行需要 JDK 21 及以上）

Windows 端装了多个 JDK 时，在 `jdk-path.txt` 里写一行 JDK 安装目录即可固定版本（路径无效会明确报错并暂停，不会悄悄改用其它版本）；也可把 JDK 21 的 `bin` 目录放到 `PATH` 最前面。

### macOS / Linux

```bash
./run.sh                # 自动查找 JDK（要求 21 及以上）
./run.sh /opt/jdk-21    # 指定 JDK 安装目录
```

`run.sh` 仍按优先级查找 JDK：命令行参数 > `PDMS_JAVA_HOME` > `jdk-path.txt` > `JAVA_HOME` > PATH 中的 `java`。

### 手动运行

```bash
mvn -o package
java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Xms1g -Xmx2g -jar target/pdms-import-tool.jar
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
   - **极速模式**（默认关闭）：PostgreSQL 走 `COPY` 批量加载，Oracle/达梦走 `INSERT /*+ APPEND */`。只有确认未提交且回滚成功的可重试错误才允许降级；超时或提交结果不确定时停止，请核对目标数据后重试。
   - 表不存在时自动建表（优先读取同目录 `*_tables.sql` 的 Oracle 字段定义并转换为目标库类型，执行前弹窗预览并可编辑）
     - PostgreSQL 建表前会自动 `CREATE SCHEMA IF NOT EXISTS`（schema 名转小写）；Oracle/达梦的 schema 即用户，不会自动创建
   - 空字符串转 NULL、失败跳过继续
5. 点击**开始导入**，下方日志与表格实时刷新进度；可随时点**停止**；结束后可**导出失败明细**

## 四、实现要点

- **流式处理**：commons-csv 流式解析 + JDBC 批处理提交，1GB 级 CSV 内存占用稳定在几十 MB
- **大表分片**：按字节区间切分，切分点保证落在行首且引号成对（不会切坏带换行的字段），每个分片独享连接与事务
- **方言抽象**：`Dialect` 收敛三种库的 URL、驱动、标识符大小写、清表语句、极速加载与类型映射
- **时间列绑定（重要）**：统一用 `setTimestamp` 配普通占位符 `?`，**不拼接 `TO_DATE(...)` / `TO_TIMESTAMP(?, '格式')`**。因为绑定值本身已是 `Timestamp`，再套日期函数会让 Oracle 先按会话 `NLS_TIMESTAMP_FORMAT`（默认形如 `28-SEP-26 09.59.47.000000000 AM`）隐式转成字符串，再按给定格式解析，从而报 `ORA-01858（在要求输入数字处找到非数字字符）`
- **值绑定与校验**：时间列宽容解析 `2026-09-28`、`2026-09-28 09:59:47`、`2026-09-28 09:59:47.123`、`2026/9/28`、`2026-9-8 9:5:3`、带 `T` 分隔等写法；数字列严格解析；**绑定阶段**发现格式非法时只记录该行并跳过，失败原因用中文写明列名与值（如「列 CREATE_TIME 的值「药道医疗」不是合法日期时间」），不会因个别坏行导致整表失败
- **Oracle 表定义优先**：缺表时读取 CSV 同目录的 `_tables.sql` 或 `kbe_tables.sql` 等 `*_tables.sql` 文件，按表名和列名匹配，保持 CSV 字段顺序，使用当前连接的目标 Schema。仅提取字段类型，不执行脚本中的约束、索引、默认值和注释语句。
  - `VARCHAR2(n)` 转为目标字符类型；达梦使用 `VARCHAR(n CHAR)`，按字符数而不是字节数限制，避免中文内容在相同字段长度下报 `-6169`。达梦字符列超过 2047 字符时保守转换为 CLOB，避免字符展开后的字节容量超过旧版字段限制；缺少源长度单位时采用字符语义以容纳完整文本。`NUMBER(p,s)` 转为对应精度的数值类型，`CLOB` 转为目标大文本类型；Oracle `DATE` 在 PostgreSQL/金仓/达梦中使用 `TIMESTAMP`，MySQL 使用 `DATETIME`，保留时分秒。
  - MySQL 无精度 `NUMBER` 使用 `DECIMAL(65,30)`，日志提示整数最多 35 位、小数最多 30 位；超出范围的数据需要手工确认。MySQL 宽表按 utf8mb4 估算列宽，必要时把较长 `VARCHAR` 转为 `LONGTEXT`。
  - 文件、表或列定义缺失时仍使用 CSV 采样；同名表跨 Schema 时优先匹配目标 Schema，无法唯一匹配时提示并使用采样。已定义但无法可靠转换的类型、损坏或重复定义的脚本会明确报告错误，不生成该表 DDL。
  - 达梦新表附加 `STORAGE(USING LONG ROW)`，允许变长字段跨页存储。已有表报 `-2665 记录超长` 时需停止导入，由管理员确认后执行 `ALTER TABLE "schema"."table" ENABLE USING LONG ROW;`，再选择清表后完整重导，避免已成功数据重复。
  - 脚本沿用界面所选文件编码。已有表和刚创建的表均在清表前查询实际列元数据，按 CSV 列顺序绑定；字符列保留编码前导零，数值列使用 BigDecimal，手工编辑后的 DDL 以目标表实际类型为准。目标列缺失、无查询权限或类型不支持时停止该表导入，不执行清表。
  - 可选开启“按源脚本同步表和字段注释”：默认关闭，独立保存到配置。解析同目录 `*_tables.sql` 中的 `COMMENT ON TABLE`、`COMMENT ON COLUMN`，同步表注释和 CSV 使用列的注释；未声明的注释保持原样，显式空字符串或 NULL 表示清空。新建表、新增列及已有列均支持，差异统一预览确认后执行；MySQL 保留完整列属性，其他数据库逐条执行 `COMMENT ON`，验证失败则不清表、不导入该表。普通 `--`、`/* */` 脚本说明不写入数据库。
  - 可选开启“按源脚本补充缺失字段”：默认关闭，与自动建表、字符扩长互不依赖。只补充 CSV 使用且 `_tables.sql` 有可靠类型定义的列；预览 SQL 只读，确认后逐列新增、提交并验证，再清表导入。新增列统一允许 NULL，不复制源默认值、非空、主外键、唯一性及其他约束；预览明确提示这一差异。源定义缺失或无法转换时不按 CSV 采样猜测。新增失败或预览后该列已出现时停止该表，重新预览只处理仍缺失的列。
  - 可选开启“按源脚本扩长已有字符字段”：导入前预览同类型字符列的扩长 SQL，确认后在清表前逐条执行并验证；只扩长，不缩短、不改类型、不新增列。扩长失败或预览后定义变化时停止该表，已完成的 DDL 保留并在日志说明，重试时重新比较。
- **类型推断**：采样前 5000 行判断整数 / 精确小数 / 日期 / 日期时间 / 长文本 / 字符串，用于缺少源定义时的自动建表。前导零编码保留为字符串，超出 Long 范围的整数使用 DECIMAL；Oracle 采样小数列使用不固定小数位的 `NUMBER`。
  - 新表仅靠采样建成数值列、采样外才出现前导零时，标准模式仍记录类型冲突及完整原文；需要保留前导零的编码应将目标列定义为字符类型。已有数值列以其实际类型为准。
  - 失败日志创建失败时不启动导入；运行中日志写入失败会显式报错，避免失败明细静默丢失。
- **失败行精确定位**：批量提交失败先回滚，再判断错误类型
  - 数据行问题（约束冲突、数值/长度超限等，SQLState 22/23/21 类）→ **逐行重放**该批：每行前打 `Savepoint`，坏行回滚后逐条记录，好行照常入库，因此**每个坏行都能精确记录**
  - 环境性问题（连接中断、表/列不存在、无权限等）→ 整批只记录 1 条，避免同一个错误刷出上千条明细
  - 明细字段：表名、文件行号、错误码（SQLState/错误码）、错误原因、**完整原始 CSV 行**（可直接修复后重放）
- **失败明细三重保障**：① 实时写入 `~/.pdms-import-tool/logs/failures_时间戳.tsv`（每条 flush）；② 界面日志区红字回显前 300 条；③ 内存保留最多 2 万条且原始数据合计不超过约 1600 万字符，可点「导出失败明细」另存。完整数据以磁盘日志为准，TSV 使用标准引号转义，原始 CSV 的长字段、换行及制表符不截断。
- **极速模式**（可选，默认关闭）：批量加载通道比逐行 INSERT 快数倍
  - PostgreSQL / 人大金仓：CSV 字节流交服务端解析；使用 `NULL ''`，空串转 NULL 时对全部导入列指定 `FORCE_NULL`，保留空串时指定 `FORCE_NOT_NULL`，使带引号和不带引号的空字段行为一致，字面量 `\N` 保持原文；多分片可并行 COPY。目标库不支持相应 COPY 选项时，按现有回滚与降级机制改用标准写入。
  - Oracle/达梦：`INSERT /*+ APPEND */` 直接路径插入；因直接路径插入对表加排它锁，**自动降为单分片写入**
  - 不开启并行 DML（不执行 `ALTER SESSION ENABLE PARALLEL DML`）：并行 DML 会让每条语句启动 PX 从属进程并在提交时等待回收，在 `parallel_max_servers` 紧张或表并行度不合理时会造成长时间阻塞，收益不稳定
  - **保守降级**：COPY/LOAD DATA 在执行失败且确认回滚成功后可改用标准 INSERT；APPEND 仅在尚未产生成功或失败行结果时允许从头重试。提交失败、回滚失败、连接被超时终止时禁止自动重放，避免重复写入。
- **写入超时与自动中断**（防止一直卡在"提交中"）：
  - 连接层：`ConnectionFactory` 统一设置 JDBC 网络读取超时（未配置写入超时时按 10 分钟兜底），Oracle 额外设置 `oracle.net.READ_TIMEOUT`，避免数据库端无响应时 JDBC 调用无限期挂起
  - 批次层：每批 `executeBatch` 前设置查询超时（等于「写入超时秒数」）
  - 看门狗：等待日志不刷新进展时间。标准批次按执行/提交开始时间、COPY/LOAD DATA 按最后一次实际数据进展检测超时，超时后异步终止连接；驱动不支持 abort 时尝试关闭连接。
  - 取消/超时错误由 `SqlErrors.isCancelOrTimeout` 识别并**强制上抛**，不会被"失败跳过继续"当作普通坏行静默吞掉（否则整批丢失但整表仍显示成功）
  - 结果：超时后停止该分片，不自动降级重放；提交阶段中断时请先核对目标数据。含失败行的表不会显示为全部成功；取消后等待运行任务实际退出再汇总，未提交尾批不再写入。
- **性能实测**（本机 1.13GB / 218 万行 CSV，仅客户端解析，不含数据库）：单线程 13.6 万行/s（70.6 MB/s），6 分片并行 28.8 万行/s（149.5 MB/s）——客户端不会成为瓶颈，实际速度取决于数据库写入能力

## 五、Windows 打包为 exe（可选）

在有 JDK 21+ 的 Windows 机器上，可用 `jpackage` 生成本地安装包（需先安装 [WiX 3.x](https://wixtoolset.org/) 才能生成 msi）：

```bat
mvn -o package
jpackage --type exe --name pdms-import-tool ^
  --input target --main-jar pdms-import-tool.jar ^
  --main-class com.moral.Launcher --java-options "-Dfile.encoding=UTF-8 -Xms1g -Xmx2g" ^
  --icon icons\PDMS.ico ^
  --win-console
```

macOS 生成 dmg：

```bash
jpackage --type dmg --name pdms-import-tool \
  --input target --main-jar pdms-import-tool.jar \
  --main-class com.moral.Launcher --java-options "-Dfile.encoding=UTF-8 -Xms1g -Xmx2g" \
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
| 一直卡在"提交中"不动 | 单批超过「写入超时秒数」（默认 120 秒）后会终止连接并标记失败，不自动重放。提交阶段中断需先核对目标数据。慢库可调大超时；填 0 关闭看门狗中断，仍保留连接层网络超时兜底。 |
| 导入速度还能怎么提 | ① 勾选极速模式；② 调大并发线程数与批量提交行数；③ 临时把索引/约束置为不可用、导入后重建；④ 确认目标库 redo/归档与磁盘 IO 不是瓶颈 |
| 有失败行，怎么定位 | 三条途径：日志区红字 `[失败行] 表名 第 N 行：错误码 原因`、实时落盘文件 `~/.pdms-import-tool/logs/failures_时间戳.tsv`、点「导出失败明细」另存为表格文件；表名后带 `[分片N/M]` 表示来自大表分片，行号为该分片内的序号 |
| Windows 提示文件被占用 | 关闭 Excel 等占用 CSV 的程序后重试 |
| 导入速度慢 | 调大并发线程数与批量提交行数；确认目标库 redo / 归档空间充足 |

## 七、目录结构

```
pdms-import-tool/
├── pom.xml                  # 依赖与 shade 打包配置（编译目标 Java 21）
├── run.sh / run.bat         # 启动脚本（Windows 端直接用 PATH 中的 java）
├── jdk-path.txt             # JDK 路径配置模板（run.bat / run.sh 均读取，可选）
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

配置文件保存在用户主目录 `~/.pdms-import-tool/config.properties`（连接信息、导入选项、上次目录）。点击“保存配置”、开始导入或正常关闭窗口时，会同时保存密码；重启后自动回填密码框，界面仍以掩码显示。密码以明文保存在本机配置文件中；旧版未保存的密码需重新输入并保存一次。

大文本批次同时受行数与约 8 MiB 的保守字符数据量估算限制，先达到任一阈值即提交；单条超过阈值的记录完整独立写入。批次及回滚同时失败时保留原始错误，禁止自动重放。
