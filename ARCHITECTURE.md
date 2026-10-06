# 两种 Android 客户端方案的实践记录

## 网页版外壳

`showdown-android-web` 中的 WebView 访问官方新版网页客户端。官网负责界面、对战协议、队伍编辑、更新；Android 外壳负责全屏窗口、文件选择与外链。官网改版时可立即获得新版界面，不过与手机浏览器同样依赖 WebView 和网络，也继承网页布局及兼容性问题。

## 原生实验版

本项目把 UI、对战状态与队伍存储移到 Android：

1. `ShowdownSocket` 连接文档中的 `wss://sim3.psim.us/showdown/websocket`，执行 TLS 主机名验证和 WebSocket 握手，收发文本帧。
2. `MainActivity` 按 `>ROOMID` 分流消息，解析 `|request|`、`|switch|`、`|move|`、`|-damage|` 等事件。选择指令携带 `rqid`，由服务器判断是否合法。
3. “我的队伍”和“编辑队伍”先显示原生队伍列表与六个席位；`TeamCatalog` 从官网当前数据生成宝可梦、道具、特性和招式候选，原生配置页写入 `TeamStore`。高级入口由 `OfficialTeambuilderView` 打开官网组队器，其导出文本与 packed team 可同步回 Android 私有数据。合法性由官网组队器的 Validate 和服务器匹配校验。
4. 命名与登录使用服务器 `|challstr|`。临时昵称通过官方 `getassertion` 接口取得 assertion；已有账号将密码通过 HTTPS 发给官方登录接口。两者最终均通过 `/trn` 认证。密码不持久化。
5. `DexNames` 从 APK 内的中英名称表加载招式及宝可梦名称。展示时用中文，发给服务器的选招槽位和队伍导出仍使用协议要求的形式。名称表由 `tools/update_dex_names.py` 从 PokéAPI 生成。
6. 投降与计时器直接使用官方 `/forfeit`、`/timer on`、`/timer off` 指令；计时器界面依据 `|inactive|` 和 `|inactiveoff|` 事件更新。
7. 战斗精灵图按需从官网加载：Android 9 以上优先解码动态 GIF，缺失或加载失败时回退到 PNG。原生视图还对上场、攻击和倒下添加简短位移与透明度变化。
8. `BattleBackdropView` 为战斗区域提供本地绘制的默认场景；联网时叠加官网背景图，`|-weather|` 事件用于切换天气场景。
9. `MoveEffectsView` 接收实时 `|move|` 事件，通过本地招式元数据决定类型配色、物理/特殊/变化表现、威力和名称特例。动画排队播放；进入房间时的历史日志不回放特效。它是原生重绘方案，并非官网逐帧动画脚本的移植。

这个版本重点验证官方协议、网络重连、选招与效果呈现的边界。现已记录多个房间并提供切换入口、匹配计时和触屏配队；后续仍需完整的双打目标操作、回放、跨设备队伍备份、设置和持续兼容测试。
