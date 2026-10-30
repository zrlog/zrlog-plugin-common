package com.zrlog.plugin.client;

import com.google.gson.Gson;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.RunConstants;
import com.zrlog.plugin.api.*;
import com.zrlog.plugin.common.IOUtil;
import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugin.common.vo.ServiceInvokeResult;
import com.zrlog.plugin.data.codec.ContentType;
import com.zrlog.plugin.data.codec.HttpRequestInfo;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.message.CapabilityInvokeRequest;
import com.zrlog.plugin.message.CapabilityInvokeResult;
import com.zrlog.plugin.message.CapabilityCancelRequest;
import com.zrlog.plugin.message.InitConnectRequest;
import com.zrlog.plugin.message.PluginProcessInfo;
import com.zrlog.plugin.message.ServiceRequest;
import com.zrlog.plugin.type.ActionType;
import com.zrlog.plugin.type.RunType;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by xiaochun on 2016/2/12.
 */
public class ClientActionHandler implements IActionHandler {

    private static final Logger LOGGER = LoggerUtil.getLogger(ClientActionHandler.class);
    private final CapabilityInvocationControl invocationControl = new CapabilityInvocationControl();

    public static String ACTION_NOT_FOUND_PAGE = "<html><body><h1>Not Found</h1></body></html>";

    @Override
    public int capabilityLifecycleVersion() {
        // Subclasses may still implement asynchronous legacy callbacks or views.
        return getClass() == ClientActionHandler.class ? 2 : 0;
    }

    @Override
    public void service(IOSession session, MsgPacket msgPacket) {
        String serviceName = null;
        if (msgPacket.getContentType() == ContentType.JSON) {
            ServiceRequest request = new Gson().fromJson(msgPacket.getDataStr(), ServiceRequest.class);
            serviceName = request == null ? null : request.getName();
        }
        if (!handleServiceByName(session, msgPacket, serviceName).isOk()) {
            session.sendJsonMsg(new HashMap<>(), msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_ERROR);
        }
    }

    @Override
    public void capabilityInvoke(IOSession session, MsgPacket msgPacket) {
        CapabilityInvokeRequest request = new Gson().fromJson(msgPacket.getDataStr(), CapabilityInvokeRequest.class);
        if (request != null && request.isRequireCompletion()) {
            invokeToCompletion(session, msgPacket, request);
            return;
        }
        ServiceInvokeResult serviceInvokeResult = handleServiceByName(session, msgPacket, request.getCapabilityKey());
        if (serviceInvokeResult.isOk()) {
            return;
        }
        CapabilityInvokeResult result = new CapabilityInvokeResult();
        result.setSuccess(false);
        result.setErrorMessage(serviceInvokeResult.getMessage());
        session.sendJsonMsg(result, msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_ERROR);
    }

    private void invokeToCompletion(IOSession session, MsgPacket packet, CapabilityInvokeRequest request) {
        if (!validRequestId(request.getRequestId())) {
            session.close();
            return;
        }
        CapabilityInvocationControl.Execution execution = invocationControl.begin(session, packet.getMsgId(), request.getRequestId());
        if (execution == null) {
            // A duplicate must not acknowledge termination of the original still-running invocation.
            session.close();
            return;
        }
        boolean alreadyInterrupted = Thread.currentThread().isInterrupted();
        CapabilityInvokeResult result = unknownOutcome();
        try (IOSession.DeferredResponse deferred = session.deferResponse(packet.getMsgId())) {
            if (execution.isCancelled() || request.getDeadlineEpochMillis() != null
                    && request.getDeadlineEpochMillis() <= System.currentTimeMillis()) {
                result = new CapabilityInvokeResult();
                result.setErrorMessage("Invocation cancelled before execution");
            } else if (request.getViewRequest() != null) {
                result = renderViewToCompletion(session, packet, request, deferred);
            } else {
                ServiceInvokeResult invocation = handleServiceByName(session, packet, request.getCapabilityKey());
                MsgPacket response = deferred.getPacket();
                if (invocation.isOk() && response != null && response.getContentType() == ContentType.JSON
                        && packet.getMethodStr().equals(response.getMethodStr())) {
                    CapabilityInvokeResult parsed = new Gson().fromJson(response.getDataStr(), CapabilityInvokeResult.class);
                    if (parsed != null && parsed.isSuccess() == (response.getStatus() == MsgPacketStatus.RESPONSE_SUCCESS)) result = parsed;
                } else if (!invocation.isOk()) {
                    result.setErrorMessage(invocation.getMessage());
                }
            }
        } catch (RuntimeException error) {
            result = unknownOutcome();
        } catch (Error fatal) {
            result = unknownOutcome();
            throw fatal;
        } finally {
            if (execution.isCancelled()) {
                Thread.interrupted(); // Sending the final acknowledgement must survive our own cancellation interrupt.
                result.setSuccess(false);
                result.setOutcomeUncertain(true);
                result.setRenderedText(null);
                result.setErrorMessage("Invocation cancelled; review its effects before retrying");
            }
            result.setExecutionCompleted(true);
            try {
                session.sendJsonMsg(result, packet.getMethodStr(), packet.getMsgId(),
                        result.isSuccess() ? MsgPacketStatus.RESPONSE_SUCCESS : MsgPacketStatus.RESPONSE_ERROR);
            } finally {
                invocationControl.finish(execution);
                if (alreadyInterrupted) Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void capabilityCancel(IOSession session, MsgPacket packet) {
        CapabilityCancelRequest request = new Gson().fromJson(packet.getDataStr(), CapabilityCancelRequest.class);
        if (request == null || !validRequestId(request.getRequestId())) return;
        invocationControl.cancel(session, packet, request);
    }

    private CapabilityInvokeResult renderViewToCompletion(IOSession session, MsgPacket invocation,
                                                          CapabilityInvokeRequest request, IOSession.DeferredResponse deferred) {
        HttpRequestInfo view = request.getViewRequest();
        if (!CapabilityInvokeRequest.HOST_VIEW_CAPABILITY.equals(request.getCapabilityKey())
                || view.getHttpMethod() != com.zrlog.plugin.common.type.HttpMethod.GET
                || view.getUri() == null || !view.getUri().startsWith("/") || view.getUri().contains("\\"))
            throw new IllegalArgumentException("Invalid host view request");
        for (String segment : view.getUri().split("/"))
            if ("..".equals(segment) || ".".equals(segment)) throw new IllegalArgumentException("Invalid host view path");
        if (request.isViewResource()) return renderViewResource(request);
        ActionType action = ActionType.HTTP_METHOD;
        MsgPacket packet = new MsgPacket(view, ContentType.JSON, MsgPacketStatus.SEND_REQUEST, invocation.getMsgId(), action.name());
        httpMethod(session, packet);
        MsgPacket response = deferred.getPacket();
        if (response == null || !action.name().equals(response.getMethodStr()) || response.getData() == null
                || response.getContentType() == ContentType.FILE
                || response.getData().remaining() > CapabilityInvokeRequest.MAX_VIEW_RESPONSE_BYTES)
            return unknownOutcome();
        CapabilityInvokeResult result = new CapabilityInvokeResult();
        result.setSuccess(response.getStatus() == MsgPacketStatus.RESPONSE_SUCCESS);
        if (result.isSuccess()) {
            java.nio.ByteBuffer bytes = response.getData().asReadOnlyBuffer();
            byte[] content = new byte[bytes.remaining()];
            bytes.get(content);
            result.setRenderedText(new String(content, java.nio.charset.StandardCharsets.UTF_8));
        } else result.setErrorMessage("Plugin view rendering failed");
        return result;
    }

    private CapabilityInvokeResult renderViewResource(CapabilityInvokeRequest request) {
        CapabilityInvokeResult result = new CapabilityInvokeResult();
        try (InputStream input = ClientActionHandler.class.getResourceAsStream("/templates" + request.getViewRequest().getUri())) {
            if (input == null) { result.setErrorMessage("Plugin view resource was not found"); return result; }
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (true) {
                if (Thread.currentThread().isInterrupted() || request.getDeadlineEpochMillis() != null
                        && request.getDeadlineEpochMillis() <= System.currentTimeMillis())
                    throw new IllegalStateException("Plugin view resource budget exhausted");
                int count = input.read(buffer);
                if (count < 0) break;
                if (output.size() + count > CapabilityInvokeRequest.MAX_VIEW_RESPONSE_BYTES)
                    throw new IllegalStateException("Plugin view resource exceeds the inline response limit");
                output.write(buffer, 0, count);
            }
            result.setSuccess(true);
            result.setRenderedText(new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            return result;
        } catch (java.io.IOException error) { throw new IllegalStateException("Plugin view resource read failed", error); }
    }

    private static boolean validRequestId(String requestId) {
        return requestId != null && requestId.matches("[a-zA-Z0-9_.-]{1,128}");
    }

    private static CapabilityInvokeResult unknownOutcome() {
        CapabilityInvokeResult result = new CapabilityInvokeResult();
        result.setOutcomeUncertain(true);
        result.setErrorMessage("Plugin handler did not return a confirmed outcome");
        return result;
    }

    @Override
    public void pluginProcessQuery(IOSession session, MsgPacket msgPacket) {
        try {
            PluginProcessInfo info = PluginProcessInfoCollector.current(session == null ? null : session.getPlugin());
            session.sendJsonMsg(info, msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS);
        } catch (RuntimeException e) {
            PluginProcessInfo info = new PluginProcessInfo();
            info.setAlive(Boolean.FALSE);
            info.setSampledAt(System.currentTimeMillis());
            info.setErrorMessage(e.getMessage() == null ? "query plugin process failed" : e.getMessage());
            session.sendJsonMsg(info, msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_ERROR);
        }
    }

    private ServiceInvokeResult handleServiceByName(IOSession session, MsgPacket msgPacket, String name) {
        if (msgPacket.getContentType() != ContentType.JSON) {
            return new ServiceInvokeResult(400, "not support service " + name);
        }
        List<Class<? extends IPluginService>> pluginServices = (List<Class<? extends IPluginService>>) session.getAttr().get("_pluginServices");
        if (pluginServices == null || pluginServices.isEmpty()) {
            return new ServiceInvokeResult(404, "Service is empty");
        }
        try {
            for (Class<? extends IPluginService> serviceClass : pluginServices) {
                if (supportServiceName(serviceClass, name)) {
                    serviceClass.getDeclaredConstructor().newInstance().handle(session, msgPacket);
                    return new ServiceInvokeResult(0, "");
                }
            }
            return new ServiceInvokeResult(400, "Capability not found: " + name);
        } catch (Exception e) {
            return new ServiceInvokeResult(500, "handle service method error " + e.getMessage());
        }
    }

    private boolean supportServiceName(Class<? extends IPluginService> serviceClass, String name) {
        Service service = serviceClass.getAnnotation(Service.class);
        if (service != null && service.value().equals(name)) {
            return true;
        }
        Capability capability = serviceClass.getAnnotation(Capability.class);
        if (capability != null && capability.key().equals(name)) {
            return true;
        }
        ScheduledCapability scheduledCapability = serviceClass.getAnnotation(ScheduledCapability.class);
        return scheduledCapability != null && scheduledCapability.key().equals(name);
    }

    @Override
    public void initConnect(IOSession session, MsgPacket msgPacket) {
        InitConnectRequest request = new Gson().fromJson(msgPacket.getDataStr(), InitConnectRequest.class);
        RunConstants.runType = RunType.valueOf(request.getRunType());
        IConnectHandler connectHandler = (IConnectHandler) session.getAttr().get("_connectHandle");
        if (connectHandler != null) {
            connectHandler.handler(session, msgPacket);
        }
    }

    @Override
    public void getFile(IOSession session, MsgPacket msgPacket) {
        HttpRequestInfo httpRequestInfo = new Gson().fromJson(msgPacket.getDataStr(), HttpRequestInfo.class);
        InputStream in = ClientActionHandler.class.getResourceAsStream("/templates" + httpRequestInfo.getUri());
        if (in != null) {
            byte[] tmpBytes = IOUtil.getByteByInputStream(in);
            session.sendMsg(ContentType.BYTE, tmpBytes, msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS, null);
        } else {
            session.sendMsg(ContentType.BYTE, new byte[]{}, msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_ERROR, null);
        }
    }

    @Override
    public void loadWebSite(IOSession session, MsgPacket msgPacket) {

    }

    @Override
    public void setWebSite(IOSession session, MsgPacket msgPacket) {

    }

    @Override
    public void httpMethod(IOSession session, MsgPacket msgPacket) {
        if (msgPacket.getStatus() == MsgPacketStatus.RESPONSE_SUCCESS || msgPacket.getStatus() == MsgPacketStatus.RESPONSE_ERROR) {
            return;
        }
        List<Class> clazzList = (List<Class>) session.getAttr().get("_actionClassList");
        HttpRequestInfo httpRequestInfo = new Gson().fromJson(msgPacket.getDataStr(), HttpRequestInfo.class);
        if (clazzList != null && !clazzList.isEmpty()) {
            for (Class clazz : clazzList) {
                try {
                    Method method = clazz.getMethod(httpRequestInfo.getUri().replace("/", "").replace(".action", ""));
                    Constructor constructor = clazz.getConstructor(IOSession.class, MsgPacket.class, HttpRequestInfo.class);
                    method.invoke(constructor.newInstance(session, msgPacket, httpRequestInfo));
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException |
                         InstantiationException e) {
                    LOGGER.log(Level.SEVERE, "", e);
                    session.sendMsg(ContentType.HTML, ACTION_NOT_FOUND_PAGE, msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_ERROR, null);
                }
            }
        }
    }

    @Override
    public void deleteComment(IOSession session, MsgPacket msgPacket) {

    }

    @Override
    public void addComment(IOSession session, MsgPacket msgPacket) {

    }

    @Override
    public void plugin(IOSession session, MsgPacket msgPacket) {
        ActionType action = ActionType.valueOf(msgPacket.getMethodStr());
        IPluginAction pluginAction = null;
        try {
            pluginAction = ((Class<IPluginAction>) session.getAttr().get("_pluginClass")).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            LOGGER.log(Level.SEVERE, "", e);
        }
        if (pluginAction != null) {
            if (action == ActionType.PLUGIN_INSTALL) {
                HttpRequestInfo httpRequestInfo = new Gson().fromJson(msgPacket.getDataStr(), HttpRequestInfo.class);
                pluginAction.install(session, msgPacket, httpRequestInfo);
            } else if (action == ActionType.PLUGIN_START) {
                pluginAction.start(session, msgPacket);
            } else if (action == ActionType.PLUGIN_UNINSTALL) {
                pluginAction.uninstall(session, msgPacket);
                System.exit(1);
            } else if (action == ActionType.PLUGIN_STOP) {
                pluginAction.stop(session, msgPacket);
                System.exit(1);
            }
        }
    }

    @Override
    public void getDbProperties(IOSession session, MsgPacket msgPacket) {

    }

    @Override
    public void attachment(IOSession session, MsgPacket msgPacket) {

    }

    @Override
    public void loadPublicInfo(IOSession session, MsgPacket msgPacket) {

    }

    @Override
    public void getCurrentTemplate(IOSession session, MsgPacket msgPacket) {

    }

    @Override
    public void getBlogRuntimePath(IOSession session, MsgPacket msgPacket) {

    }

    @Override
    public void createArticle(IOSession session, MsgPacket msgPacket) {

    }

    @Override
    public void refreshCache(IOSession session, MsgPacket msgPacket) {

    }

    @Override
    public void articleVisitViewCountAddOne(IOSession session, MsgPacket msgPacket) {

    }
}
