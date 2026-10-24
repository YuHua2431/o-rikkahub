<div align="center">
  <img src="docs/icon.png" alt="App 图标" width="100" />
  <h1>o-rikkahub</h1>

一个原生 Android LLM 聊天客户端，支持切换不同的供应商进行聊天 🤖💬

[English](README.md) | 简体中文

点击链接加入原作者的群聊 👉 [【RikkaHub】](https://qm.qq.com/q/I8MSU0FkOu)

</div>

<div align="center">
  <img src="docs/img/chat.png" alt="Chat Interface" width="150" />
  <img src="docs/img/desktop.png" alt="Models Picker" width="450" />
</div>

## 🧩 改动

本项目基于 RikkaHub **2.5.6**（[原仓库](https://github.com/rikkahub/rikkahub)），在保留 2.5.6 时代
经典图标与界面风格的前提下增加 **3.x** 功能——旧外观，新能力。

### 移植自 3.x

- 设置图标样式
- 媒体生成（mediagen 模块：图像 / 视频生成）
- 会话重构（agent 层、ConversationSession、ChatService 对齐）
- 检查点压缩
- 记忆文件化（含前台服务与记忆 UI）
- 用量统计页
- DeepSeek 搜索
- 新的 Markdown 渲染管线
- 消息层对齐（分支、附件、操作等）

### 本 fork 新增

- 侧边栏添加 AI 翻译和媒体生成入口
- 新增生成图片的 skill：模型可自行生成图片

## ✨ 开发

> [!IMPORTANT]
> 本项目基于 vibe coding 开发，使用 DeepSeek V4.1 1017 与 Mimo V2.6 Pro。

技术栈文档:

- [Kotlin](https://kotlinlang.org/) (开发语言)
- [Koin](https://insert-koin.io/) (依赖注入)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) (UI 框架)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore?hl=zh-cn#preferences-datastore) (
  偏好数据存储)
- [Room](https://developer.android.com/training/data-storage/room) (数据库)
- [Coil](https://coil-kt.github.io/coil/) (图片加载)
- [Material You](https://m3.material.io/) (UI 设计)
- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3) (导航)
- [Okhttp](https://square.github.io/okhttp/) (HTTP 客户端)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (Json序列化)

> [!TIP]
> 你需要在 `app` 文件夹下添加 `google-services.json` 文件才能构建应用。

## 💰 捐赠

以下捐赠链接属于 RikkaHub 原作者（reovo / [rikkahub](https://github.com/rikkahub/rikkahub)），不属于本
fork。

* [Patreon](https://patreon.com/rikkahub)
* [爱发电](https://afdian.com/a/reovo)

## 📄 许可证

本项目基于 [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0) 开源。
