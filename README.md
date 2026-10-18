# CodeI18n IDEA

读取项目 `.codei18n` 翻译缓存，在 IntelliJ 系 IDE 的源码注释下方（或原位）显示译文。原注释仍可编辑；译文由编辑器 inlay / 折叠区域绘制，不会写入源码、复制出的代码或 Git diff。

## 安装与兼容范围

版本 **0.2.0**，兼容 **IntelliJ 平台 2025.1 – 2026.2**（`since-build="251"`，`until-build="262.*"`），在 IntelliJ IDEA 2025.1 Community 与 2026.2 Ultimate 上做过真实 IDE 集成测试。插件只依赖平台模块，Rider、WebStorm 等同一平台版本的 IDE 也可安装；没有语言插件的文件走下文的“无 PSI 回退”。

1. 构建或取得 `build/distributions/codei18n-idea-0.2.0.zip`。
2. Settings → Plugins → 齿轮 → Install Plugin from Disk，选择 ZIP。
3. 按提示重启，打开含 `.codei18n` 的项目与源码文件。
4. Settings → CodeI18n：开关显示、填写显示语言（`zh-CN`、`zh-TW`、`ja` 等，留空跟随项目配置）、选择显示模式。

示例：

- `examples/demo`：带手写 `.codei18n` 的最小 Java 示例，无需 CLI 或联网（示例 ID 仅作展示）。
- `examples/languages`：Kotlin、Java、TypeScript、JavaScript、Dart、C# 各一个文件，每个文件都有块注释、2–3 行连续的 `//` 行注释、文档注释，以及字符串里的 `//`（不是注释，不应显示译文）。`.codei18n` 由 CLI 生成（mock 翻译，译文形如 `[MOCK en->zh-CN] ...`），可直接打开体验。

## 数据文件与显示语言

`.codei18n/` 下插件读取三个文件：

- `config.json`：`{"sourceLanguage":"en","localLanguage":"zh-CN"}`；
- `mappings.json`：注释 ID → 语言 → 文本，例如 `"comments": {"<id>": {"en": "// Calculate account balance", "zh-CN": "计算账户余额"}}`；
- `index.json`（可选，`codei18n map update` 生成）：每条注释所在的文件、位置、类型、原语言、文本哈希等，格式见 CodeI18n 仓库的 `docs/index-format.md`。

显示语言优先级：IDE 项目设置 → config 的 `localLanguage` → mappings 的 `targetLanguage` → `zh-CN`。插件从源码所在目录向项目根目录查找最近的 `.codei18n`，支持一个仓库里有多个子项目（嵌套根目录）。

## 匹配规则

对每个打开的源码文件，插件先用 IDE 的 PSI 找出注释，再按以下优先级为每条注释（或注释组）查找译文：

1. **index.json 精确匹配（文件未改动）**：磁盘文件的 SHA-1 等于 index.json 中该文件的 `contentHash`，且编辑器里的文档正是磁盘上的这份内容（没有未保存的修改，IDE 也已载入最新的磁盘内容）时，按“文件 + 起始行列 + 物理注释条数”直接命中条目，再按条目 ID 取译文。原文相同、译文不同的两条注释因此能各自显示正确的译文。
2. **textHash 匹配（文件已改动）**：文件内容与索引不一致时，只在该文件的条目里按归一化文本的 `textHash`（及条数）匹配。同一文本出现多次且数量与索引一致时按出现顺序配对，否则取行号最近的一条，距离相同则不显示。因此插入、删除代码行后译文仍跟着注释走。
3. **全局文本匹配（兼容没有 index.json 的老项目）**：没有 index.json，或文件不在索引里、上面都未命中时，按注释文本在整个 mappings.json 中反查。同一原文对应多个不同译文，或部分记录缺少译文时视为有歧义，不显示。

其他规则：

- **连续行注释合并**：与 core 的规则一致（`docs/index-format.md` §6.1）——标记相同（`//`、`///`、`//!` 互不相同）、行号连续、起始列相同、各自独占一行的行注释合并为一条记录。PSI 中相邻的多个注释先按同样规则分组再匹配，**整组只显示一条译文，位于组的最后一行下方**；整组匹配不上时退回逐条匹配（兼容按行存储的旧映射）。
- **原语言**：index.json 记录每条注释的原语言（`lang`）和原文所在的键（`sourceKey`）。原文已经是显示语言时不显示（不会把注释“翻译”成它自己）；缺少显示语言的译文、或译文与原文相同时也不显示。没有索引时，除显示语言外的任何语言都可能是原文，因此用本地语言写的注释也能显示另一种语言的译文。
- **语言码等价**：`zh`、`zh-CN`、`zh-SG`、`zh-Hans` 视为同一种语言，`zh-TW`、`zh-HK`、`zh-Hant` 视为另一种；`en-US` 与 `en` 等价。设置里填 `zh` 也能找到 mappings 中 `zh-CN` 的译文（规则与 core 共享测试向量）。
- 编辑某条注释会立即撤下它自己的译文，其他译文不动；停止输入约 350 ms 后重新匹配。字符串里的 `//`、`/*` 不是注释，不会显示译文。

## 无 PSI 回退

文件没有任何注释 PSI 时（例如 Rider 中的 C#、没装 Dart 插件的 IDE、IntelliJ IDEA Community 中的 TypeScript/JavaScript——这些文件按纯文本或 TextMate 文件打开），插件直接使用 index.json 记录的范围：取文档中该范围的文本，归一化后的 `textHash` 与索引一致才显示译文，否则宁可不显示。因此回退模式需要 index.json，且只对仍与索引一致的注释生效。

回退模式下文档注释同样按语言专项渲染：C# 的 XML 文档注释去掉标签后简洁显示（`<summary>` 正文、`参数 x: …`、`返回: …`，`<c>`、`<paramref>` 等保留高亮），Dartdoc 的 `[引用]` 与反引号代码按文档样式显示。

## 显示模式与快捷键

- **下方显示**（默认）：译文以 block inlay 显示在注释（组）下方。
- **替换显示**：用折叠区域把原注释替换为译文。光标进入注释时露出原文（译文临时显示在下方），离开约 0.6 秒后重新折叠；单击译文可把光标移入原注释。文档内容始终不变。
- `Ctrl+Alt+Shift+O`：开关译文显示；`Ctrl+Alt+Shift+P`：在“下方显示 / 替换显示”之间切换。两项也在编辑器右键菜单中（Show CodeI18n Translations、Replace Comments with CodeI18n Translations），快捷键可在 Keymap 中修改。
- Tools → Reload CodeI18n Translations：强制重新读取所有缓存。

## 自动刷新

除监听 IDE 文件系统事件外，插件每秒在后台检查已打开源码对应的 mappings/config/index 文件元数据，检测到变化后自动刷新，无需切换窗口或手动重载。集成测试实测：新打开的编辑器约 0.4 秒显示译文；外部改写 mappings.json 后约 0.4 秒更新；切换分支（源码与 `.codei18n` 一起变化）后约 0.6 秒显示新译文。CLI 原地改写 JSON 时读到不完整内容会短暂重试，文件稳定后仍无效才通知并撤下该缓存的译文。

切换分支时，如果 `.codei18n` 在 IDE 重新载入源码之前就已更新，插件在 IDE 载入新内容前不按位置匹配（磁盘内容已不是编辑器里的文本），只按 textHash 匹配，避免把译文挂到别的注释下。JSON 被原地改写且大小、时间戳都不变时，元数据检查可能发现不了，可用 Reload 强制刷新。

## 生成缺失译文

插件只读取和显示已有缓存，不调用翻译服务、不保存 API 密钥。用 [CodeI18n CLI](https://github.com/studyzy/codei18n) 在项目根目录生成缓存：

```shell
codei18n init --provider <provider>
codei18n map update
codei18n translate
```

`map update` 同时写出 mappings.json 与 index.json。新增语言可用 `codei18n translate --target ja`。本插件不需要 `convert`。`.codei18n/cache/` 是增量扫描缓存，不必入库。注意 `translate` 的标准输入不是终端时会改为翻译标准输入的内容，在脚本中运行请把标准输入接到终端或空设备（如 `< /dev/null`）。

## 边界

- 译文使用原注释语言的语法高亮和当前配色，按 IDE 的 TODO 规则高亮；Kotlin KDoc 的 `[引用]`、Javadoc 的 `{@link}`/`@see`/`@param`、JSDoc、Dartdoc、C# XML 文档注释按各自规则显示。引用不做符号解析。
- 插件不自行计算注释 ID，ID 视为不透明字符串。
- 大于 2 MiB 的源码不处理；大于 32 MiB 的 JSON 拒绝加载并通知。

## 构建与测试

Windows PowerShell，无需下载 Gradle 或 IntelliJ SDK，直接用已安装的 IDE 编译和测试。两个兼容端点都要测：

```powershell
# IntelliJ IDEA 2026.2（262）
.\build.ps1    -IdeaHome 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.1'
.\test-ide.ps1 -IdeaHome 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.1'

# IntelliJ IDEA 2025.1（251），替换为本机安装目录
.\build.ps1    -IdeaHome '<IDEA 2025.1 安装目录>'
.\test-ide.ps1 -IdeaHome '<IDEA 2025.1 安装目录>'
```

`build.ps1` 默认用该 IDE 自带的 JBR 编译（可用 `-JdkHome` 指定 JDK 25+），输出 Java 21 字节码，执行映射/缓存/文档注释等回归测试后生成插件 ZIP；构建产物不打包 IDE 依赖。也可用 Gradle：`gradle -PideaHome="<IDE 目录>" buildPlugin`。

`test-ide.ps1` 在 `build/integration` 下用隔离的配置、系统、插件目录启动真实的无界面 IDE（不影响日常使用的 IDE 配置），覆盖：

- `examples/demo`：Java PSI、block inlay、语言切换、编辑注释即时撤下译文、外部改写/替换/删除/损坏 mapping 与 config 后自动恢复、显示模式切换、主题配色；
- `examples/languages`（每次复制到 `build/integration/project/languages`）：六种语言逐一打开，断言块注释、合并后的行注释组（只有一条译文，位于最后一行之后）、文档注释（C# 断言去掉标签后的显示文本）的精确译文，以及字符串里的 `//` 没有译文；记录并断言每种语言走的路径——Kotlin/Java 在两个版本都走 PSI，TypeScript/JavaScript 在 2026.2 走 PSI、在 2025.1 走回退，Dart/C# 在两个版本都走回退；
- 自动刷新：打开即显示；外部改写译文（Kotlin 存储的原文被改掉后仍能显示，证明经 index.json 匹配）；分支切换（`integration/fixtures/branch-switch` 是用 CLI 生成的新源码与新 `.codei18n`），两种先后顺序都断言译文不会挂错位置。各项延迟以 `LATENCY` 输出到日志；
- 截图：真实编辑器组件（含行号、语法高亮与译文）绘制为 `build/integration/screenshots/<IDE>/<语言>.png`，另有替换模式的 `java-replace.png`。截图不入库。

结果、日志（`stdout.log`）与截图都在 `build/integration`。测试辅助插件只在隔离环境加载，不进入发行 ZIP。

`test-rider.ps1`（`-RiderHome` 指定 Rider 目录）在 `build/integration-rider` 下用隔离目录无头启动 Rider，只测 `Ledger.cs`：断言与上面的 C# 样例相同，并截图到 `build/integration-rider/screenshots/rider/csharp.png`。**Rider 无头启动需要有效许可证**；在没有许可证的隔离配置中会以 `No valid license found`（退出码 7）结束，所以这个脚本目前没有跑通过。没有许可证时请按 PR 中的步骤手动验证。

修改 `examples/languages` 的源码后，用 CLI 重新生成它的 `.codei18n`（在该目录依次运行 `codei18n map update`、`codei18n translate`；首次需 `codei18n init --provider mock`），删除生成的 `cache/`，并同步更新 `integration/fixtures/branch-switch`。
