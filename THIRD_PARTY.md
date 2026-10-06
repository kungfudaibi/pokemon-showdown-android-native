# 第三方来源与权利

| 内容 | 用途 | 来源与许可 |
| --- | --- | --- |
| [Pokémon Showdown 服务器](https://github.com/smogon/pokemon-showdown) | 本应用连接其公开协议，服务器不在 APK 中 | 上游 [MIT 许可](https://github.com/smogon/pokemon-showdown/blob/master/LICENSE) |
| [Pokémon Showdown 网页客户端](https://github.com/smogon/pokemon-showdown-client) | 官方战斗模式在运行时从 `play.pokemonshowdown.com` 加载脚本、样式和素材；高级配队页打开官网 | 上游 [AGPL-3.0](https://github.com/smogon/pokemon-showdown-client/blob/master/LICENSE)；本仓库不分发其脚本整包 |
| [PokéAPI CSV](https://github.com/PokeAPI/pokeapi/tree/master/data/v2/csv) | 生成 `assets/dex_names.tsv` 中的中英名称资料 | 上游许可文本随包保留在 [`assets/POKEAPI_LICENSE.txt`](assets/POKEAPI_LICENSE.txt) |
| `assets/title-incineroar.*`、`assets/title-garchomp.*`、`res/drawable-nodpi/app_icon.png` | 非官方标题画面及应用图标 | Pokémon／Showdown 相关角色、图案和标识的权利保留给各自权利人；本项目不对其主张 AGPL 授权 |
| `assets/title-stadium.png` | 标题画面背景 | 为本项目制作的背景图；若其中元素涉及第三方形象，相应权利仍保留 |

本仓库中 `official_battle.html` 和 `team_catalog.html` 是本项目编写的加载页面；页面引用的官网 URL 会在运行时获取原站资源。源代码的 AGPL 声明仅适用于本项目有权许可的部分，不能替代上游和角色素材的权利声明。此项目没有获得 Nintendo、The Pokémon Company 或 Pokémon Showdown 团队认可。
