# Pokémon Showdown Android 原生实验版（非官方）

本项目针对 [Pokémon Showdown 对战服务器仓库](https://github.com/smogon/pokemon-showdown)的公开协议，以及 [官方网页客户端仓库](https://github.com/smogon/pokemon-showdown-client)的战斗显示方式，**新写**了 Android 入口、对战控件、房间切换、队伍管理和触屏配置流程。匹配、合法性、回合结算仍由官方服务器处理；战斗画面可在运行时加载官网的渲染脚本、样式和素材。本仓库没有复制或修改官方服务器源码，也没有把官方网页客户端整包打入 APK。它不是官方客户端。

## 下载与定位

在本仓库的 [Releases](https://github.com/kungfudaibi/pokemon-showdown-android-native/releases) 下载 APK。此版本适合测试手机端交互，仍是实验版；如果只想直接使用官网界面，也可以在手机浏览器打开 [官方网页版](https://play.pokemonshowdown.com/)。本项目优先把大厅、房间、选招和配队改成手机操作方式，同时复用官网对战规则和可选的战斗渲染器。

## 当前实现

- 使用官方协议连接、登录、匹配和观战。大厅可查看正在进行的房间、切换对战，并显示匹配已等待的时间。
- 规则列表由服务器下发；规则旁可查看世代、OU、Ubers 等简要说明。自建队伍可按所选格式匹配，具体合法性仍以官网校验和服务器结果为准。
- 横屏对战页显示官方动画或本地简化特效；支持招式、换人、首发、可用时的 Mega／极巨化／太晶化选项，以及投降和计时器请求。
- 本机队伍列表、六个席位与触屏配置：宝可梦、招式、道具、特性、性格、EV、IV、等级和太晶属性等。可重新编辑已选宝可梦；复杂配置可进入官网组队器。
- 中文名称表和招式说明从官方数据及 [PokéAPI](https://github.com/PokeAPI/pokeapi)资料整理，来源和许可见 [第三方声明](THIRD_PARTY.md)。

## 已知限制

- 对战控件重点针对单打；双打目标选择与所有特殊规则尚未完整覆盖。遇到官网协议或资源变化，可能出现画面或操作不兼容。
- 战斗画面的官方模式要联网加载官网脚本和素材；本地特效按招式数据生成，不等同于官网逐帧动画。
- 队伍保存在应用私有数据中。账号断线后可能需要重新登录；尚无完善的云同步和跨设备备份。
- 内置部分角色图像用于非官方开场封面，相关角色、名称和美术权利不因本仓库源码许可而转移。

更细的实现说明见 [ARCHITECTURE.md](ARCHITECTURE.md)，当前测试清单见 [TEAM_BUILDER_ACCEPTANCE.md](TEAM_BUILDER_ACCEPTANCE.md)。

## 构建

需要 JDK 17+、Android SDK（Android 36 平台、Build Tools 36.0.0）。设置 `JAVA_HOME` 和 `ANDROID_HOME` 后运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

输出 `build/showdown-native.apk`。`signing/dev.keystore` 是本地测试签名密钥，不纳入 Git；覆盖安装后续自建包时应保留自己的密钥。Android 8.0 以上可安装。

## 许可与上游

本项目自写 Android 源码按 [AGPL-3.0-only](LICENSE) 发布，以贴近 [官方网页客户端的 AGPL-3.0](https://github.com/smogon/pokemon-showdown-client/blob/master/LICENSE)。[对战服务器](https://github.com/smogon/pokemon-showdown)使用 MIT 许可；本 APK 只通过协议连接，不包含服务器代码。运行时加载的官网资源和随包分发的图像、资料按各自来源的许可或权利声明处理，详见 [THIRD_PARTY.md](THIRD_PARTY.md)。Pokémon 和 Showdown 相关商标、美术与角色并未获得官方授权。
