package com.zrlog.plugin.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.RunConstants;
import com.zrlog.plugin.api.*;
import com.zrlog.plugin.common.ConfigKit;
import com.zrlog.plugin.common.IOUtil;
import com.zrlog.plugin.common.IdUtil;
import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.data.codec.SocketCodec;
import com.zrlog.plugin.data.codec.SocketDecode;
import com.zrlog.plugin.data.codec.SocketEncode;
import com.zrlog.plugin.data.codec.SocketPacketLimits;
import com.zrlog.plugin.data.codec.SocketPacketMemoryBudget;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugin.render.IRenderHandler;
import com.zrlog.plugin.type.ActionType;
import com.zrlog.plugin.type.RunType;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class NioClient {

    static final int MESSAGE_HANDLER_THREADS = 4;
    static final int MESSAGE_HANDLER_QUEUE_CAPACITY = 8;
    static final long SOCKET_PACKET_MEMORY_BUDGET_BYTES = SocketPacketLimits.DEFAULT_MAX_DATA_LENGTH_BYTES;
    private static final Gson GSON = new Gson();
    private final IConnectHandler connectHandler;
    private final IRenderHandler renderHandler;
    private final IActionHandler actionHandler;
    private final AtomicBoolean exitRequested = new AtomicBoolean(false);

    public NioClient() {
        this(null);
    }

    public NioClient(IActionHandler actionHandler) {
        this(null, null, actionHandler);
    }

    public NioClient(IConnectHandler connectHandler, IRenderHandler renderHandler) {
        this(connectHandler, renderHandler, null);
    }

    public NioClient(IConnectHandler connectHandler, IRenderHandler renderHandler, IActionHandler actionHandler) {
        this.connectHandler = connectHandler;
        this.renderHandler = renderHandler;
        if (actionHandler == null) {
            this.actionHandler = new ClientActionHandler();
        } else {
            this.actionHandler = actionHandler;
        }
    }

    public void connectServer(String[] args, List<Class<?>> classList, Class<? extends IPluginAction> pluginAction) throws IOException {
        connectServer(args, classList, pluginAction, new ArrayList<>());
    }

    public void connectServer(String[] args, List<Class<?>> classList, Class<? extends IPluginAction> pluginAction, Class<? extends IPluginService> service) throws IOException {
        List<Class<? extends IPluginService>> serviceList = new ArrayList<>();
        serviceList.add(service);
        connectServer(args, classList, pluginAction, serviceList);
    }

    public void connectServer(String[] args, List<Class<?>> classList, Class<? extends IPluginAction> pluginAction, List<Class<? extends IPluginService>> serviceList) throws IOException {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS.%1$tL %4$s %5$s%6$s%n");
        Plugin plugin = new Plugin();
        String propertiesPath = "/plugin.properties";
        try (InputStream in = NioClient.class.getResourceAsStream(propertiesPath)) {
            if (in == null) {
                throw new IOException("not found properties file " + propertiesPath);
            }
            Properties properties = new Properties();
            properties.load(in);
            plugin.setVersion(properties.getProperty("version", ""));
            plugin.setName(properties.getProperty("name", ""));
            plugin.setDesc(properties.getProperty("desc", ""));
            plugin.setDependentService(propertySet(properties, "dependentService"));
            plugin.setPaths(propertySet(properties, "paths"));
            plugin.setActions(propertySet(properties, "actions"));
            plugin.setCacheableStaticPaths(readCacheableStaticPaths(properties));
            plugin.setShortName(properties.getProperty("shortName", ""));
            plugin.setAuthor(properties.getProperty("author", ""));
            plugin.setIndexPage(properties.getProperty("indexPage", ""));
            try (InputStream inputStream = NioClient.class.getResourceAsStream("/preview-image.base64")) {
                if (inputStream != null) {
                    plugin.setPreviewImageBase64(new String(IOUtil.getByteByInputStream(inputStream)));
                } else {
                    plugin.setPreviewImageBase64("");
                }
            }
        }
        if (serviceList != null && !serviceList.isEmpty()) {
            for (Class<? extends IPluginService> serviceClass : serviceList) {
                Service service = serviceClass.getAnnotation(Service.class);
                if (service == null) {
                    throw new RuntimeException("forget add @Service in the Class " + serviceClass);
                }
                plugin.getServices().add(service.value());
                plugin.getCapabilities().addAll(readCapabilities(serviceClass));
            }
        }
        //parse args
        int serverPort = ConfigKit.getServerPort();
        if (args != null && args.length > 0) {
            serverPort = Integer.parseInt(args[0]);
        }
        if (args != null && args.length > 1) {
            plugin.setId(args[1]);
        }
        if (Objects.isNull(plugin.getId())) {
            plugin.setId(UUID.randomUUID().toString());
        }
        for (PluginCapability capability : plugin.getCapabilities()) {
            fillPluginInfo(plugin, capability);
        }
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", serverPort);
        connectServer(serverAddress, classList, plugin, pluginAction, serviceList);
    }

    private static Set<String> propertySet(Properties properties, String key) {
        Set<String> values = new LinkedHashSet<>();
        String value = properties.getProperty(key);
        if (value == null) {
            return values;
        }
        for (String item : value.split(",")) {
            String itemValue = item.trim();
            if (!itemValue.isEmpty()) {
                values.add(itemValue);
            }
        }
        return values;
    }

    private static Set<String> readCacheableStaticPaths(Properties properties) {
        Set<String> paths = new LinkedHashSet<>();
        for (String path : propertySet(properties, "cacheableStaticPaths")) {
            addNormalizedCacheableStaticPath(paths, path);
        }
        paths.addAll(readAssetManifestStaticPaths("/asset-manifest.json"));
        paths.addAll(readAssetManifestStaticPaths("/templates/asset-manifest.json"));
        return paths;
    }

    private static Set<String> readAssetManifestStaticPaths(String resourcePath) {
        try (InputStream inputStream = NioClient.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                return new LinkedHashSet<>();
            }
            return parseAssetManifestStaticPaths(new String(IOUtil.getByteByInputStream(inputStream), StandardCharsets.UTF_8));
        } catch (Exception e) {
            LoggerUtil.getLogger(NioClient.class).log(Level.WARNING, "read asset manifest " + resourcePath + " error", e);
            return new LinkedHashSet<>();
        }
    }

    static Set<String> parseAssetManifestStaticPaths(String assetManifestJson) {
        Set<String> paths = new LinkedHashSet<>();
        if (assetManifestJson == null || assetManifestJson.trim().isEmpty()) {
            return paths;
        }
        JsonObject manifest = GSON.fromJson(assetManifestJson, JsonObject.class);
        if (manifest == null) {
            return paths;
        }
        collectStaticPaths(manifest, paths);
        return paths;
    }

    private static void collectStaticPaths(JsonElement element, Set<String> paths) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            addNormalizedCacheableStaticPath(paths, element.getAsString());
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectStaticPaths(child, paths);
            }
            return;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                collectStaticPaths(entry.getValue(), paths);
            }
        }
    }

    private static void addNormalizedCacheableStaticPath(Set<String> paths, String value) {
        String path = normalizeCacheableStaticPath(value);
        if (path != null) {
            paths.add(path);
        }
    }

    private static String normalizeCacheableStaticPath(String value) {
        if (value == null) {
            return null;
        }
        String path = value.trim();
        if (path.isEmpty()) {
            return null;
        }
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }
        int fragmentIndex = path.indexOf('#');
        if (fragmentIndex >= 0) {
            path = path.substring(0, fragmentIndex);
        }
        int staticIndex = path.indexOf("/static/");
        if (staticIndex >= 0) {
            path = path.substring(staticIndex);
        } else if (path.startsWith("static/")) {
            path = "/" + path;
        }
        return path.startsWith("/static/") ? path : null;
    }

    private void connectServer(InetSocketAddress serverAddress, List<Class<?>> classList, Plugin plugin, Class<? extends IPluginAction> pluginAction, List<Class<? extends IPluginService>> serviceList) {
        try (SocketChannel socketChannel = SocketChannel.open()) {
            socketChannel.configureBlocking(false);
            Selector selector = Selector.open();

            Set<SelectionKey> selectionKeys;
            socketChannel.register(selector, SelectionKey.OP_CONNECT);
            socketChannel.connect(serverAddress);
            IOSession session = null;
            SocketDecode socketDecode = null;
            while (selector.isOpen()) {
                selector.select();
                selectionKeys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = selectionKeys.iterator();
                while (iterator.hasNext()) {
                    try {
                        SelectionKey selectionKey = iterator.next();
                        SocketChannel channel = (SocketChannel) selectionKey.channel();
                        if (selectionKey.isConnectable()) {
                            if (channel.isConnectionPending()) {
                                channel.finishConnect();
                            }
                            socketDecode = newSocketDecode(newMessageHandlerExecutor());
                            session = new IOSession(channel, selector, new SocketCodec(new SocketEncode(), socketDecode), actionHandler, renderHandler);
                            exitWhenSessionCloses(session);
                            session.setPlugin(plugin);
                            session.getAttr().put("_actionClassList", classList);
                            session.getAttr().put("_pluginClass", pluginAction);
                            session.getAttr().put("_pluginServices", serviceList);
                            if (connectHandler != null) {
                                session.getAttr().put("_connectHandle", connectHandler);
                            }
                            IOSession finalSession = session;
                            session.sendJsonMsg(plugin, ActionType.INIT_CONNECT.name(), IdUtil.getInt(), MsgPacketStatus.SEND_REQUEST, (msgPacket) -> {
                                actionHandler.initConnect(finalSession, msgPacket);
                            });
                        } else if (selectionKey.isReadable()) {
                            if (Objects.isNull(session)) {
                                throw new RuntimeException("socketDecode is null");
                            }
                            while (!socketDecode.doDecode(session)) {
                                Thread.sleep(20);
                            }
                        }
                    } catch (Exception e) {
                        exitPlugin(e);
                    } finally {
                        iterator.remove();
                    }
                }
                //selectionKeys.clear();
            }
        } catch (Exception e) {
            exitPlugin(e);
        }
    }

    static ThreadPoolExecutor newMessageHandlerExecutor() {
        return new ThreadPoolExecutor(MESSAGE_HANDLER_THREADS, MESSAGE_HANDLER_THREADS,
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MESSAGE_HANDLER_QUEUE_CAPACITY),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    static SocketPacketMemoryBudget newSocketPacketMemoryBudget() {
        return new SocketPacketMemoryBudget(SOCKET_PACKET_MEMORY_BUDGET_BYTES);
    }

    static SocketDecode newSocketDecode(Executor messageHandlerExecutor) {
        return new SocketDecode(messageHandlerExecutor, newSocketPacketMemoryBudget());
    }

    List<PluginCapability> readCapabilities(Class<? extends IPluginService> serviceClass) {
        List<PluginCapability> capabilities = new ArrayList<>();
        Service service = serviceClass.getAnnotation(Service.class);
        Capability capability = serviceClass.getAnnotation(Capability.class);
        ScheduledCapability scheduledCapability = serviceClass.getAnnotation(ScheduledCapability.class);
        if (scheduledCapability != null) {
            if (capability != null && !capability.key().equals(scheduledCapability.key())) {
                throw new RuntimeException("@Capability key must equal @ScheduledCapability key in " + serviceClass);
            }
            capabilities.add(fromScheduledCapability(scheduledCapability, capability, service));
        } else if (capability != null) {
            capabilities.add(fromCapability(capability, service));
        }
        if (RunConstants.runType == RunType.AGENT) {
            try {
                serviceClass.getConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return capabilities;
    }

    private PluginCapability fromCapability(Capability capability, Service service) {
        PluginCapability pluginCapability = new PluginCapability();
        pluginCapability.setKey(capability.key());
        pluginCapability.setServiceName(service == null ? null : service.value());
        pluginCapability.setType(capability.type());
        pluginCapability.setLabel(capability.label());
        pluginCapability.setDescription(capability.description());
        pluginCapability.setExposure(Arrays.asList(capability.exposure()));
        pluginCapability.setRiskLevel(capability.riskLevel());
        pluginCapability.setReadOnly(capability.readOnly());
        pluginCapability.setRequiresConfirmation(capability.requiresConfirmation());
        pluginCapability.setTimeoutSeconds(capability.timeoutSeconds());
        pluginCapability.setConcurrency(capability.concurrency());
        pluginCapability.setEnabled(Boolean.TRUE);
        pluginCapability.setChannel(capability.channel());
        return pluginCapability;
    }

    private PluginCapability fromScheduledCapability(ScheduledCapability scheduledCapability, Capability capability, Service service) {
        PluginCapability pluginCapability = new PluginCapability();
        pluginCapability.setKey(scheduledCapability.key());
        pluginCapability.setServiceName(service == null ? null : service.value());
        pluginCapability.setType("scheduled");
        pluginCapability.setLabel(scheduledCapability.label());
        pluginCapability.setDescription(scheduledCapability.description());
        pluginCapability.setExposure(Collections.singletonList("scheduler"));
        pluginCapability.setRiskLevel(capability == null ? "low" : capability.riskLevel());
        pluginCapability.setReadOnly(capability == null ? Boolean.FALSE : capability.readOnly());
        pluginCapability.setRequiresConfirmation(capability == null ? Boolean.FALSE : capability.requiresConfirmation());
        pluginCapability.setTimeoutSeconds(scheduledCapability.timeoutSeconds());
        pluginCapability.setConcurrency(scheduledCapability.concurrency());
        pluginCapability.setEnabled(Boolean.TRUE);
        pluginCapability.setDefaultCron(scheduledCapability.defaultCron());
        pluginCapability.setTimezone(scheduledCapability.timezone());
        return pluginCapability;
    }

    private void fillPluginInfo(Plugin plugin, PluginCapability capability) {
        if (capability.getPluginId() == null) {
            capability.setPluginId(plugin.getId());
        }
        if (capability.getPluginName() == null) {
            capability.setPluginName(plugin.getShortName());
        }
    }

    void exitWhenSessionCloses(IOSession session) {
        session.addCloseListener(() -> exitPlugin(null));
    }

    private void exitPlugin(Exception e) {
        if (!exitRequested.compareAndSet(false, true)) {
            return;
        }
        if (e == null || isSocketSessionClosed(e)) {
            LoggerUtil.getLogger(NioClient.class).info("Plugin session closed");
        } else {
            LoggerUtil.getLogger(NioClient.class).log(Level.SEVERE, "", e);
        }
        terminateProcess(0);
    }

    static boolean isSocketSessionClosed(Exception e) {
        return e instanceof SocketDecode.SocketSessionClosedException;
    }

    void terminateProcess(int status) {
        System.exit(status);
    }
}
