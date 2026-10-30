# zrlog-plugin-common

ZrLog 插件公共库，提供插件协议消息、客户端连接工具、模板渲染接口、通知渠道查询 DTO 等共享代码；本项目不作为独立插件安装。

更多插件开发说明见 [ZrLog 插件开发文档](https://blog.zrlog.com/zrlog-plugin-dev.html)。

## 有预算的任务与页面渲染

默认 `ClientActionHandler` 声明完成协议版本 2：能力及页面处理器返回前结束业务，SDK 延迟最终响应并支持取消。
自定义 handler（包括子类）默认仍为版本 0，避免把异步旧实现误报为已完成。
完成适配后可覆盖 `IActionHandler.capabilityLifecycleVersion()` 返回 2。旧 `refreshCache` 工作需迁到声明的
`event_handler`；宿主页面调用只能分发到可同步完成的读取入口，其他入口应在执行前明确拒绝。
仅升级依赖不会自动为自定义 handler 开启完成协议。

## 版本与发布

本分支的开发版本为 `4.0.7-SNAPSHOT`，包含有预算的执行与页面完成协议；开发快照用于跨工程集成。父 POM、`zrlog-plugin-common` 和 `zrlog-plugin-freemarker-render` 使用同一版本。正式发布前应统一切换到已确认的正式版本；正式标签必须与非快照 POM 版本一致；正式版本在 `validate` 阶段拒绝父 POM 和直接或传递依赖中的 SNAPSHOT。

`4.0.5` 包含文章扩展协议、通知渠道查询、插件进程信息、Socket 资源限制与会话关闭处理。消费者使用了这些接口，不能直接回退到 `4.0.4`。

父工程、common 和 freemarker-render 的版本在代码 PR 中统一审阅。正式发布由 release-ci bot 按独立发布单创建与 POM 一致的不可变 `vX.Y.Z` 标签，触发 Maven Central 发布并核对制品。PR 和手动分支构建只做验证；`main` 在 POM 明确使用 `-SNAPSHOT` 时发布开发快照到 Sonatype snapshots。快照版本在代码中明确审阅，不自动推算下一个版本。公共库独立发版，不绑定 ZrLog 主工程的发布依赖链。

发布顺序：先发布公共库并确认 Central 上的 POM/JAR 可解析，再合入、构建关联插件。主题同样先发布 `zrlog-template-spi`，再发布主题，最后升级应用依赖。正式版本及其 tag 不覆盖，后续改动使用新的明确版本号。

开发验证使用 `./mvnw clean verify`。`python3 scripts/check-snapshot-publication.py` 向临时文件仓库部署，并在全新 Maven 缓存中验证消费者解析；不会上传远端。开发快照的发布使用 `./mvnw -Psnapshot deploy`，只由主分支 CI 携带凭据执行。
