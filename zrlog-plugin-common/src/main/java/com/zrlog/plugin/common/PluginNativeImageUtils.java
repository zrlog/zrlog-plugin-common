package com.zrlog.plugin.common;

import com.google.gson.Gson;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.common.bucket.BucketVO;
import com.zrlog.plugin.common.model.*;
import com.zrlog.plugin.common.response.UploadFileResponseEntry;
import com.zrlog.plugin.data.codec.*;
import com.zrlog.plugin.message.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

public class PluginNativeImageUtils {

    public static void doLoopResourceLoad(File[] files, String basePath, String uriStart) {
        if (Objects.isNull(files)) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                doLoopResourceLoad(file.listFiles(), basePath, uriStart);
            } else {
                String binPath = file.toString().substring(basePath.length());
                String rFileName = uriStart + binPath.replace("\\", "/");
                try (InputStream inputStream = PluginNativeImageUtils.class.getResourceAsStream(rFileName)) {
                    if (Objects.nonNull(inputStream)) {
                        LoggerUtil.getLogger(PluginNativeImageUtils.class).info("Native image add filename " + rFileName);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public static void exposeController(List<Class<?>> classList) {
        classList.forEach(e -> {
            Constructor constructor;
            try {
                constructor = e.getConstructor(IOSession.class, MsgPacket.class, HttpRequestInfo.class);
                for (Method method : e.getDeclaredMethods()) {
                    if (!Modifier.isPublic(method.getModifiers())) {
                        continue;
                    }
                    try {
                        method.invoke(constructor.newInstance(null, null, null));
                    } catch (Exception ex) {
                        LoggerUtil.getLogger(PluginNativeImageUtils.class).log(Level.WARNING,
                                "Expose controller method failed: " + e.getName() + "#" + method.getName(), ex);
                    }
                }
            } catch (Exception ex) {
                LoggerUtil.getLogger(PluginNativeImageUtils.class).log(Level.WARNING,
                        "Expose controller failed: " + e.getName(), ex);
            }
        });
    }

    public static void gsonNativeAgentByClazz(List<Class<?>> cls) {
        Gson gson = new Gson();
        for (Class<?> cl : cls) {
            try {
                Class<?> clazz = Class.forName(cl.getName());
                Object o = gson.fromJson("{}", clazz);
                gson.toJson(o);
            } catch (Exception e) {
                LoggerUtil.getLogger(PluginNativeImageUtils.class).severe("gsonNativeAgentByClazz " + cl.getName() + "  error: " + e.getMessage());
            }
        }
    }

    public static void usedGsonObject() {
        gsonNativeAgentByClazz(Arrays.asList(FileDesc.class, HttpRequestInfo.class, BaseHttpRequestInfo.class, HttpResponseInfo.class,
                Plugin.class, BlogRunTime.class, Comment.class, CreateArticleRequest.class, PublicInfo.class,
                TemplatePath.class, UploadFileResponseEntry.class, User.class, BucketVO.class,
                PluginCapability.class, CapabilityInvokeRequest.class, CapabilityInvokeResult.class, NotificationRequest.class,
                NotificationChannelProvider.class, NotificationChannelQueryResult.class,
                SchedulerQueryRequest.class, SchedulerQueryResult.class, SchedulerUpdateRequest.class, SchedulerUpdateResult.class,
                PluginProcessInfo.class, InitConnectRequest.class, ServiceRequest.class, WebsiteKeyRequest.class,
                DbPropertiesResponse.class, ArticleExtensionArticle.class, ArticleExtensionFilter.class,
                ArticleExtensionGetRequest.class, ArticleExtensionQueryRequest.class, ArticleExtensionQueryResult.class,
                ArticleExtensionResult.class, ArticleExtensionSetRequest.class));
    }
}
