# zrlog-plugin-common

ZrLog 插件公共库，提供插件协议消息、客户端连接工具、模板渲染接口、通知渠道查询 DTO 等共享代码；本项目不作为独立插件安装。

更多插件开发说明见 [ZrLog 插件开发文档](https://blog.zrlog.com/zrlog-plugin-dev.html)。

## 版本与发布

当前待发布版本为 `4.0.5`；父 POM、`zrlog-plugin-common` 和 `zrlog-plugin-freemarker-render` 使用同一版本。关联插件固定依赖正式版本，Maven 在 `validate` 阶段拒绝项目、父 POM 和直接或传递依赖中的 SNAPSHOT。

`4.0.5` 包含文章扩展协议、通知渠道查询、插件进程信息、Socket 资源限制与会话关闭处理。消费者使用了这些接口，不能直接回退到 `4.0.4`。

运行 `bash shell/version.sh 4.0.5`（兼容旧参数 `5`）统一修改模块版本并执行 `clean verify`。脚本只准备本地改动；审阅并提交后，推送与 POM 一致的 `v4.0.5` tag 才会发布 Maven Central。`main`、PR 和手动分支构建只做验证；不再发布快照，也不自动生成下一个 SNAPSHOT。

发布顺序：先发布公共库并确认 Central 上的 POM/JAR 可解析，再合入、构建关联插件。主题同样先发布 `zrlog-template-spi`，再发布主题，最后升级应用依赖。正式版本及其 tag 不覆盖，后续改动使用新的明确版本号。
