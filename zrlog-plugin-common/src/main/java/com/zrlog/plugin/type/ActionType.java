package com.zrlog.plugin.type;

/**
 * Created by xiaochun on 2016/2/12.
 */
public enum ActionType {

    INIT_CONNECT(0, "插件初始化"), HTTP_FILE(0, "获取HTTP资源文件"), HTTP_METHOD(0, "处理HTTP请求方法"),
    GET_WEBSITE(0, "website"), SET_WEBSITE(0, "website"),
    PLUGIN_START(0, "插件启动"), PLUGIN_STOP(0, "插件停止"), PLUGIN_INSTALL(0, "安装插件"), PLUGIN_UNINSTALL(0, "卸载插件"),
    SERVICE(1, "注册服务，调用其他服务"),
    ADD_COMMENT(1, "添加文章评论"),
    DELETE_COMMENT(1, "删除文章评论"),
    LIST_COMMENT(1, "查询文章评论"),
    GET_DB_PROPERTIES(1, "读取程序数据库配置文件"),
    HTTP_ATTACHMENT_FILE(0, "响应HTTP附件"), LOAD_PUBLIC_INFO(0, "读取程序公开信息"),
    CURRENT_TEMPLATE(1, ""), BLOG_RUN_TIME(1, ""), CREATE_ARTICLE(1, "创建文章"),
    ARTICLE_EXTENSION_GET(1, "读取文章扩展"),
    ARTICLE_EXTENSION_SET(1, "保存文章扩展"),
    ARTICLE_EXTENSION_QUERY(1, "筛选文章扩展"),
    REFRESH_CACHE(1, "更新缓存"),
    ARTICLE_VISIT_COUNT_ADD_ONE(1, "文章浏览量增加 1"),
    CAPABILITY_INVOKE(1, "调用插件能力"),
    NOTIFICATION_PUBLISH(1, "发布标准插件通知"),
    NOTIFICATION_CHANNEL_QUERY(0, "查询标准插件通知渠道"),
    SCHEDULER_QUERY(0, "查询插件定时任务"),
    PLUGIN_PROCESS_QUERY(0, "查询插件进程资源信息"),
    @Deprecated
    SCHEDULER_UPDATE(0, "插件定时任务只读兼容入口，写入已废弃");

    private final int level;
    private final String desc;

    ActionType(int level, String desc) {
        this.level = level;
        this.desc = desc;
    }

    public int getLevel() {
        return level;
    }

    public String getDesc() {
        return desc;
    }
}
