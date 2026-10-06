# Pokémon Showdown 安卓版

这是基于 [Pokémon Showdown 对战服务器](https://github.com/smogon/pokemon-showdown)协议做的安卓客户端。我们自己做了大厅、房间切换、选招和配队界面；对战规则与胜负仍由官方服务器决定。战斗画面可以加载[官方网页客户端](https://github.com/smogon/pokemon-showdown-client)的动画和素材，也可以切换到本地的简化效果。

## 现在能玩什么

- 在大厅选择对战格式、开始匹配、观战，查看匹配已等待的时间，并在多场对战间切换。
- 横屏对战中选招、换人、选首发；规则允许时使用 Mega、极巨化或太晶化，也能操作计时器和投降。
- 创建和编辑本机队伍：选宝可梦、招式、道具、特性、性格、EV、IV 等；需要更细的配置时可以打开官网组队器。
- 查看中文名称、招式说明和常见规则的简短解释。具体队伍是否合法，以官网校验和匹配结果为准。

这仍是实验版，单打的操作比较完整，双打目标选择等规则还在补。官方动画需要联网加载；队伍暂存在手机里，尚没有跨设备同步。更多实现细节见 [架构说明](ARCHITECTURE.md)和[配队验收清单](TEAM_BUILDER_ACCEPTANCE.md)。

## 下载与构建

APK 在 [Releases](https://github.com/kungfudaibi/pokemon-showdown-android-native/releases)。自己构建需要 JDK 17+、Android SDK 36 和 Build Tools 36.0.0，设置好 `JAVA_HOME`、`ANDROID_HOME` 后运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

输出在 `build/showdown-native.apk`。`signing/dev.keystore` 是本机生成的签名文件，后续覆盖安装自己构建的版本时需要保留它。

## 许可

本项目自写代码按 [AGPL-3.0](LICENSE) 发布，与[官方网页客户端](https://github.com/smogon/pokemon-showdown-client/blob/master/LICENSE)一致。[对战服务器](https://github.com/smogon/pokemon-showdown)使用 MIT 许可；其他资料和图像来源列在[第三方说明](THIRD_PARTY.md)。这是非官方客户端。
