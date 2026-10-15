# CodeI18n IDEA

读取项目 `.codei18n` 翻译缓存，在 IntelliJ IDEA 的源码注释下方显示译文。原注释仍可编辑；译文是编辑器 block inlay，不会写入源码、复制出的代码或 Git diff。

## 安装

当前版本针对 **IntelliJ IDEA 2026.2（build 262）** 编译。其他版本暂未声明兼容。

1. 构建或取得 `build/distributions/codei18n-idea-0.1.0.zip`。
2. IDEA → Settings → Plugins → 齿轮 → Install Plugin from Disk，选择 ZIP。
3. 按 IDEA 提示重启，打开含 `.codei18n` 的项目与源码文件。
4. Settings → CodeI18n 可关闭显示，或填写 `zh-CN`、`zh-TW`、`ja` 等语言代码。留空时跟随项目配置。

可先打开 `examples/demo/Demo.java` 体验。此示例带有自己的 `.codei18n`，无需安装 CLI 或联网。示例 ID 仅作展示，不是 CLI 的语义 SHA1。

## 数据格式与语言选择

`.codei18n/config.json`：

```json
{"sourceLanguage":"en","localLanguage":"zh-CN"}
```

`.codei18n/mappings.json` 使用上游真实的 ID → 语言 → 文本格式：

```json
{
  "version": "1.0",
  "sourceLanguage": "en",
  "targetLanguage": "zh-CN",
  "comments": {
    "comment-id-from-codei18n": {
      "en": "// Calculate account balance",
      "zh-CN": "计算账户余额",
      "ja": "口座残高を計算"
    }
  }
}
```

显示语言优先级：IDEA 项目设置 → config 的 `localLanguage` → mappings 的 `targetLanguage` → `zh-CN`。原文语言优先级：config 的 `sourceLanguage` → mappings 的 `sourceLanguage` → `en`。

插件从源码所在目录向项目根目录查找最近的 `.codei18n`，支持单仓库多个子项目。读取失败会通知，并撤下该缓存的译文。修改源码后会立即撤下旧提示，并在编辑停止约 350ms 后重新匹配。映射文件经 IDEA 文件系统检测到变化后会刷新，也可以使用 Tools → Reload CodeI18n Translations 手动刷新。

## 生成缺失译文

插件负责读取和显示已有缓存，不调用翻译服务、不保存 API 密钥。用 [CodeI18n CLI](https://github.com/studyzy/codei18n) 在项目根目录生成缓存：

```shell
codei18n map update
codei18n translate
```

先按上游说明初始化配置和翻译提供商，再在交互式终端运行这些命令。新增语言可使用 `codei18n translate --target ja`。本插件不需要 `convert`；转换源码不是显示译文的必要步骤。

## 匹配方式与边界

- 使用 IDEA PSI 的 `PsiComment`，不会把字符串中的 `//` 当成注释；语言需要对应的 IDEA 语言插件。
- 译文使用原注释语言的语法高亮和当前编辑器配色，并按 IDEA 的 TODO 规则高亮。额外支持 Kotlin KDoc 的 `[引用]` 和 Java Javadoc 的 `{@link ...}`、`{@linkplain ...}`、`@see`、`@param` 引用样式；普通注释和代码片段中的方括号不会被当作文档引用。译文可以保留或省略外层注释符号，但需保留文档标签与引用标记；不会恢复翻译时丢失的标记。引用使用语言插件的文档引用配色，不做符号解析、有效性检查或复现第三方插件额外添加的高亮。
- 使用上游注释文本归一化规则反查缓存，不自行仿造各语言的语义 ID。源码移动、行号变化不会影响相同文本匹配。
- 同一原文对应多个不同译文，或部分同文记录缺失译文时，跳过该原文，避免随意选取。当前不支持通过符号 ID 消解这种歧义。
- 未翻译、未知语言、原文已改变时不显示。跨多条 PSI 行注释合并成一个缓存记录的情况尚不匹配；单条行注释和多行块注释可匹配。
- 不替换或折叠原注释，不提供在线自动补译；自动化部分是本地缓存的读取、匹配和显示刷新。
- 大于 2 MiB 的源码不遍历；大于 32 MiB 的 JSON 拒绝加载并通知。

## 构建与验证

Windows PowerShell，无需下载 Gradle 或 IntelliJ SDK：

```powershell
.\build.ps1 -IdeaHome 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.1'
```

默认使用该 IDEA 的 JBR 25 编译器。可用 `-JdkHome` 指定其他 JDK 25+。脚本编译生产代码，执行映射/缓存回归测试，然后生成插件 ZIP。构建产物只包含本插件类和资源，不打包 IDE 依赖。

也提供 Gradle 工程，使用已安装 Gradle 与 JDK 25：

```shell
gradle -PideaHome="C:/Program Files/JetBrains/IntelliJ IDEA 2026.2.1" buildPlugin
```

核心测试涵盖语言优先级、BOM、多行注释、原文改变、歧义、无效 JSON、嵌套根、缓存更新/删除、保留时间戳的替换和源码不变。

真实平台测试（先运行 `build.ps1`）：

```powershell
.\test-ide.ps1 -IdeaHome 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.1'
```

它在 `build/integration` 下使用隔离的 IDEA 配置、系统目录、插件目录和示例项目，启动真实 IDEA 平台及自带的 Java、Kotlin 插件，检查 PSI、实际 block inlay、Javadoc/KDoc 引用颜色、注释包装、代码片段不误高亮及主题配色变化；不会安装到日常使用的 IDEA 配置。结果、绘制 PNG 与日志保存在该目录。测试辅助插件只在隔离环境加载，不进入发行 ZIP。
