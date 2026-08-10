# 股市风云 (Stock Market)

服务器内炒股模组（NeoForge / MC 1.21.1）。

- 行情模拟与交易逻辑：**服务端权威**
- 客户端 UI：**ApricityUI (AUI) 硬依赖**（HTML/CSS/JS 页面渲染），客户端必须安装 ApricityUI
- AUI 依赖声明为 `side=CLIENT`：专用服务器无需 AUI 也能跑（服务端逻辑不依赖 UI）

## 技术栈

- Minecraft 1.21.1 / NeoForge 21.1.x / Java 21
- Mod ID：`stockmarket`
- 包名：`com.tanrunn.stockmarket`
- ApricityUI：`com.sighs:ApricityUI-neoforge-1.21.1:1.2.1`（implementation，硬依赖）

## 目录结构

```
src/main/java/com/tanrunn/stockmarket/
├── StockMarketMod.java          # 主类
├── StockMarketModClient.java    # 客户端入口（配置界面）
└── Config.java                  # 服务端配置
```

## 开发

```bash
./gradlew build                    # 编译 + 打包 + 单元测试
./gradlew runClient                # 启动客户端（AUI 在运行时 classpath，UI 可用）
./gradlew runServer -PserverOnly   # 启动服务端（dev：AUI 降级为 compileOnly）
```

> **AUI 服务端加载问题（上游）**：AUI 是客户端 mod，dev 环境的 `runServer` 默认会把 AUI 放进运行时 classpath，导致专用服务器加载 AUI 主类崩溃。本工程通过 `-PserverOnly` 属性在服务端运行降级为 `compileOnly` 规避；生产服务器只装本 mod 即可（`mods.toml` 中 AUI 依赖声明为 `side="CLIENT"`，服务器不校验）。等 AUI 作者修复该问题后可去掉此开关。

## 路线图

- [ ] 股票/公司实体与行情模拟（服务端）
- [ ] 玩家账户/持仓持久化
- [ ] 交易命令与 AUI 行情/交易界面
