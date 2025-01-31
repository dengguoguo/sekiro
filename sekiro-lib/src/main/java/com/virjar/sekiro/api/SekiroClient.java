package com.virjar.sekiro.api;


import com.virjar.sekiro.Constants;
import com.virjar.sekiro.api.compress.Compressor;
import com.virjar.sekiro.api.compress.EmptyCompressor;
import com.virjar.sekiro.log.SekiroLogger;
import com.virjar.sekiro.netty.client.ClientChannelHandler;
import com.virjar.sekiro.netty.client.ClientIdleCheckHandler;
import com.virjar.sekiro.netty.protocol.SekiroMessageEncoder;
import com.virjar.sekiro.netty.protocol.SekiroNatMessage;
import com.virjar.sekiro.netty.protocol.SekiroNatMessageDecoder;
import com.virjar.sekiro.utils.TextUtil;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

public class SekiroClient {
    private String serverHost;
    private int serverPort;
    private String clientId;
    private String group = "default";
    private String sekiroConnectDescription;

    private AtomicBoolean isStartUp = new AtomicBoolean(false);
    private Compressor compressor = new EmptyCompressor();

    private static Map<String, SekiroClient> allClient = new ConcurrentHashMap<>();

    private SekiroRequestHandlerManager sekiroRequestHandlerManager = new SekiroRequestHandlerManager(this);

    private Map<String, Object> context = new ConcurrentHashMap<>();


    private SekiroClient(String serverHost, int serverPort, String clientId, String group) {
        SekiroLogger.warn("版本已经过期，开源版版本sekiro请使用开源demo server版本替换:\n" +
                "开源版本代码太丑陋，且容易出现性能问题，目前已经停止维护\n" +
                "请参考如下github文档，完成到demo版本的迁移\n" +
                "https://github.com/virjar/sekiro");
        sekiroConnectDescription = "apiEntry: (" + serverHost + ":" + serverPort + ")"
                + " group:" + group + "   clientId:" + clientId;
        SekiroLogger.info("create sekiro client," + sekiroConnectDescription);
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        //@ 是一个hermes保留分隔符
        clientId = clientId.replaceAll("@", "_");
        this.clientId = clientId;
        this.group = group;
        if (TextUtil.isEmpty(group)) {
            this.group = "default";
        }
    }

    public static SekiroClient startDefaultServer(String group) {
        return start("sekiro.virjar.com", Constants.defaultNatServerPort, UUID.randomUUID().toString(), group);
    }

    public static SekiroClient startDefaultServer(String group, String clientID) {
        return start("sekiro.virjar.com", Constants.defaultNatServerPort, clientID, group);
    }

    public static SekiroClient start(String serverHost, final String clientID) {
        return start(serverHost, Constants.defaultNatServerPort, clientID, "default");
    }

    public static SekiroClient start(String serverHost, int serverPort, final String clientID) {
        return start(serverHost, serverPort, clientID, "default");
    }

    public static SekiroClient start(String serverHost, final String clientID, String group) {
        return start(serverHost, Constants.defaultNatServerPort, clientID, group);
    }


    /**
     * 开启一个长链接调用隧道，可以实现在公网服务器调用NAT网络下的手机功能
     *
     * @param serverHost 服务器地址
     * @param serverPort 服务器ip
     * @param clientID   手机id，唯一标记一个手机（请注意，一个手机应该只开启一个隧道）
     * @param group      分组，解决同一个app，在不同团队安装了不同的服务的问题。不同group
     * @return 一个client控制器实例
     */
    public static SekiroClient start(String serverHost, int serverPort, final String clientID, String group) {
        String key = serverHost + ":" + serverPort + ":" + group + ":" + clientID;
        SekiroLogger.info("sekiro client start task:" + key);
        SekiroClient sekiroClient = allClient.get(key);
        if (sekiroClient == null) {
            synchronized (SekiroClient.class) {
                sekiroClient = allClient.get(key);
                if (sekiroClient == null) {
                    sekiroClient = new SekiroClient(serverHost, serverPort, clientID, group);
                    allClient.put(key, sekiroClient);
                }
            }
        }
        sekiroClient.startInternal();
        return sekiroClient;
    }

    private Bootstrap natClientBootstrap;

    public static NioEventLoopGroup workerGroup = new NioEventLoopGroup();

    private void startInternal() {
        if (isStartUp.compareAndSet(false, true)) {
            natClientBootstrap = new Bootstrap();

            natClientBootstrap.group(workerGroup);
            natClientBootstrap.channel(NioSocketChannel.class);
            natClientBootstrap.handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel socketChannel) throws Exception {
                    socketChannel.pipeline().addLast(new SekiroNatMessageDecoder());
                    socketChannel.pipeline().addLast(new SekiroMessageEncoder());

                    socketChannel.pipeline().addLast(new ClientIdleCheckHandler(SekiroClient.this));
                    socketChannel.pipeline().addLast(new ClientChannelHandler(SekiroClient.this));
                }
            });
            SekiroLogger.info("connect to nat server at service startUp");
            connectNatServer();

        }
    }

    /**
     * 可以在运行时切换client group，比如我们把手机根据是否登陆进行分组，一个手机可以从未登录转化为登录。此时对应的group将会跟随这切换
     *
     * @param newGroup 新的groupId
     */
    public synchronized void updateGroup(String newGroup) {
        if (group.equals(newGroup)) {
            return;
        }
        SekiroLogger.info("the group update from :" + group + " to:" + newGroup);
        Channel cmdChannelCopy = cmdChannel;
        if (cmdChannelCopy != null && cmdChannelCopy.isActive()) {
            cmdChannelCopy.close();
            cmdChannel = null;
            isConnecting = false;
        }
        group = newGroup;
        if (TextUtil.isEmpty(group)) {
            this.group = "default";
        }
        connectNatServer();
    }

    //和服务器保持链接的channel
    private Channel cmdChannel = null;
    private volatile boolean isConnecting = false;

    public boolean isDestroyed() {
        return destroyed;
    }

    private volatile boolean destroyed = false;

    /**
     * 销毁客户端
     */
    public synchronized void destroyClient() {
        if (destroyed) {
            return;
        }
        SekiroLogger.info("client: " + sekiroConnectDescription + " destroy");
        callOnClientDestroy();
        destroyed = true;
        Channel cmdChannelCopy = cmdChannel;
        if (cmdChannelCopy != null && cmdChannelCopy.isActive()) {
            cmdChannelCopy.close();
        }
    }


    public synchronized void connectNatServer() {
        if (destroyed) {
            return;
        }
        Channel cmdChannelCopy = cmdChannel;
        if (cmdChannelCopy != null && cmdChannelCopy.isActive()) {
            return;
        }
        if (isConnecting) {
            SekiroLogger.warn("connect event fire already");
            return;
        }
        isConnecting = true;
        natClientBootstrap.group().submit(new Runnable() {
            @Override
            public void run() {
                SekiroLogger.info("connect to nat server...");
                Channel cmdChannelCopy = cmdChannel;
                if (cmdChannelCopy != null && cmdChannelCopy.isActive()) {
                    SekiroLogger.info("cmd channel active, and close channel,heartbeat timeout ?");
                    cmdChannelCopy.close();
                    //TODO clean up all resource
                }
                natClientBootstrap.connect(serverHost, serverPort).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture channelFuture) throws Exception {
                        isConnecting = false;
                        if (!channelFuture.isSuccess()) {
                            SekiroLogger.warn("connect to nat server failed", channelFuture.cause());
                            natClientBootstrap.group().schedule(new Runnable() {
                                @Override
                                public void run() {
                                    SekiroLogger.info("connect to nat server failed, reconnect by scheduler task start");
                                    connectNatServer();
                                }
                            }, reconnectWait(), TimeUnit.MILLISECONDS);

                        } else {
                            sleepTimeMill = 1000;
                            cmdChannel = channelFuture.channel();
                            SekiroLogger.info("connect to nat server success:" + cmdChannel);

                            SekiroNatMessage sekiroNatMessage = new SekiroNatMessage();
                            sekiroNatMessage.setType(SekiroNatMessage.C_TYPE_REGISTER);
                            sekiroNatMessage.setExtra(clientId + "@" + group);
                            cmdChannel.writeAndFlush(sekiroNatMessage);
                            callOnClientConnected();
                        }
                    }
                });
            }
        });

    }

    private static long sleepTimeMill = 1000;

    private static long reconnectWait() {

        if (sleepTimeMill > 120000) {
            sleepTimeMill = 120000;
        }

        synchronized (SekiroClient.class) {
            sleepTimeMill = sleepTimeMill + 1000;
            return sleepTimeMill;
        }

    }


    public Channel getCmdChannel() {
        return cmdChannel;
    }

    public SekiroClient registerHandler(String action, SekiroRequestHandler sekiroRequestHandler) {
        sekiroRequestHandlerManager.registerHandler(action, sekiroRequestHandler);
        return this;
    }

    public SekiroClient registerHandler(ActionHandler actionHandler) {
        sekiroRequestHandlerManager.registerHandler(actionHandler.action(), actionHandler);
        return this;
    }

    public SekiroRequestHandlerManager getSekiroRequestHandlerManager() {
        return sekiroRequestHandlerManager;
    }

    public Compressor getCompressor() {
        return compressor;
    }

    public SekiroClient setCompressor(Compressor compressor) {
        //开源版本sekiro不支持流量压缩，就算进行压缩，服务器也不支持解码
        throw new UnsupportedOperationException("can not set compressor for open source version");
    }

    public String getSekiroConnectDescription() {
        return sekiroConnectDescription;
    }

    public Object getExtend(String key) {
        return context.get(key);
    }

    public SekiroClient setExtend(String key, Object obj) {
        context.put(key, obj);
        return this;
    }

    public String getClientId() {
        return clientId;
    }

    public String getGroup() {
        return group;
    }


    private Set<SekiroClientListener> lifeCycleListener = new CopyOnWriteArraySet<>();

    public SekiroClient addSekiroClientListener(SekiroClientListener sekiroClientListener) {
        lifeCycleListener.add(sekiroClientListener);
        return this;
    }

    private void callOnClientDestroy() {
        it4Listener(new Consumer<SekiroClientListener>() {
            @Override
            public void accept(SekiroClientListener sekiroClientListener) {
                sekiroClientListener.onClientDestroy(SekiroClient.this);
            }
        });
    }


    private void callOnClientConnected() {
        it4Listener(new Consumer<SekiroClientListener>() {
            @Override
            public void accept(SekiroClientListener sekiroClientListener) {
                sekiroClientListener.onClientConnected(SekiroClient.this);
            }
        });
    }


    public void callOnClientDisConnected() {
        it4Listener(new Consumer<SekiroClientListener>() {
            @Override
            public void accept(SekiroClientListener sekiroClientListener) {
                sekiroClientListener.onClientDisConnected(SekiroClient.this);
            }
        });
        connectNatServer();
    }


    private void it4Listener(Consumer<SekiroClientListener> consumer) {
        for (SekiroClientListener sekiroClientListener : lifeCycleListener) {
            try {
                consumer.accept(sekiroClientListener);
            } catch (Exception e) {
                SekiroLogger.error("call listener failed:", e);
            }
        }
    }

    private interface Consumer<T> {
        void accept(T t);
    }

    public interface SekiroClientListener {
        void onClientDestroy(SekiroClient sekiroClient);

        void onClientConnected(SekiroClient sekiroClient);

        void onClientDisConnected(SekiroClient sekiroClient);
    }
}
