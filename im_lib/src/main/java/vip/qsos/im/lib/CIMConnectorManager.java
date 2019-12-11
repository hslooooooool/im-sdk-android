package vip.qsos.im.lib;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import vip.qsos.im.lib.coder.ClientMessageDecoder;
import vip.qsos.im.lib.coder.ClientMessageEncoder;
import vip.qsos.im.lib.coder.ImLogUtils;
import vip.qsos.im.lib.constant.CIMConstant;
import vip.qsos.im.lib.model.HeartbeatRequest;
import vip.qsos.im.lib.model.HeartbeatResponse;
import vip.qsos.im.lib.model.Message;
import vip.qsos.im.lib.model.Protobufable;
import vip.qsos.im.lib.model.ReplyBody;
import vip.qsos.im.lib.model.SentBody;

/**
 * 连接服务端管理，cim核心处理类，管理连接，以及消息处理
 *
 * @author 3979434@qq.com
 */
class CIMConnectorManager {

    private static CIMConnectorManager manager;

    private final int READ_BUFFER_SIZE = 2048;
    private final int WRITE_BUFFER_SIZE = 1024;

    private final int READ_IDLE_TIME = 120 * 1000;

    private final int CONNECT_TIME_OUT = 10 * 1000;


    private final int CONNECT_ALIVE_TIME_OUT = 150 * 1000;

    private final AtomicLong LAST_READ_TIME = new AtomicLong(0);

    private final ImLogUtils LOGGER = ImLogUtils.getLogger();

    private static final HandlerThread IDLE_HANDLER_THREAD = new HandlerThread("READ-IDLE", Process.THREAD_PRIORITY_BACKGROUND);

    private Semaphore semaphore = new Semaphore(1, true);

    private volatile SocketChannel socketChannel;

    private Context context;


    private ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);

    private ExecutorService workerExecutor = Executors.newFixedThreadPool(1, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "worker-");
        }
    });

    private ExecutorService bossExecutor = Executors.newFixedThreadPool(1, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "boss-");
        }
    });

    private ClientMessageEncoder messageEncoder = new ClientMessageEncoder();
    private ClientMessageDecoder messageDecoder = new ClientMessageDecoder();

    static {
        IDLE_HANDLER_THREAD.start();
    }

    private CIMConnectorManager(Context context) {
        this.context = context;
    }


    public synchronized static CIMConnectorManager getManager(Context context) {

        if (manager == null) {
            manager = new CIMConnectorManager(context);
        }

        return manager;

    }

    public void connect(final String host, final int port) {

        if (!CIMPushManager.isNetworkConnected(context)) {

            Intent intent = new Intent();
            intent.setPackage(context.getPackageName());
            intent.setAction(CIMConstant.IntentAction.Companion.getACTION_CONNECTION_FAILED());
            context.sendBroadcast(intent);

            return;
        }

        if (isConnected()) {
            return;
        }

        bossExecutor.execute(new Runnable() {
            @Override
            public void run() {

                if (isConnected()) {
                    return;
                }

                LOGGER.startConnect(host, port);

                CIMCacheManager.putBoolean(context, CIMCacheManager.KEY_CIM_CONNECTION_STATE, false);

                try {

                    semaphore.acquire();

                    socketChannel = SocketChannel.open();
                    socketChannel.configureBlocking(true);
                    socketChannel.socket().setTcpNoDelay(true);
                    socketChannel.socket().setKeepAlive(true);
                    socketChannel.socket().setReceiveBufferSize(READ_BUFFER_SIZE);
                    socketChannel.socket().setSendBufferSize(WRITE_BUFFER_SIZE);

                    socketChannel.socket().connect(new InetSocketAddress(host, port), CONNECT_TIME_OUT);

                    semaphore.release();

                    handelConnectedEvent();


                    int result = -1;

                    while ((result = socketChannel.read(readBuffer)) > 0) {

                        if (readBuffer.position() == readBuffer.capacity()) {
                            extendByteBuffer();
                        }

                        handelSocketReadEvent(result);

                    }

                    handelSocketReadEvent(result);

                } catch (ConnectException ignore) {
                    semaphore.release();
                    handleConnectAbortedEvent();
                } catch (SocketTimeoutException ignore) {
                    semaphore.release();
                    handleConnectAbortedEvent();
                } catch (IOException ignore) {
                    semaphore.release();
                    handelDisconnectedEvent();
                } catch (InterruptedException ignore) {
                    semaphore.release();
                }
            }
        });
    }


    public void destroy() {

        closeSession();

    }


    public void closeSession() {

        if (!isConnected()) {
            return;
        }

        try {
            socketChannel.close();
        } catch (IOException ignore) {
        } finally {
            this.sessionClosed();
        }
    }

    public boolean isConnected() {
        return socketChannel != null && socketChannel.isConnected();
    }


    public void send(final Protobufable body) {

        if (!isConnected()) {
            return;
        }

        workerExecutor.execute(new Runnable() {

            @Override
            public void run() {
                int result = 0;
                try {

                    semaphore.acquire();

                    ByteBuffer buffer = messageEncoder.encode(body);
                    while (buffer.hasRemaining()) {
                        result += socketChannel.write(buffer);
                    }

                } catch (Exception e) {
                    result = -1;
                } finally {

                    semaphore.release();

                    if (result <= 0) {
                        closeSession();
                    } else {
                        messageSent(body);
                    }
                }
            }
        });

    }


    private void sessionCreated() {
        LOGGER.sessionCreated(socketChannel);

        LAST_READ_TIME.set(System.currentTimeMillis());

        Intent intent = new Intent();
        intent.setPackage(context.getPackageName());
        intent.setAction(CIMConstant.IntentAction.Companion.getACTION_CONNECTION_SUCCESSED());
        context.sendBroadcast(intent);

    }

    private void sessionClosed() {

        idleHandler.removeMessages(0);

        LAST_READ_TIME.set(0);

        LOGGER.sessionClosed(socketChannel);

        readBuffer.clear();

        if (readBuffer.capacity() > READ_BUFFER_SIZE) {
            readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
        }

        Intent intent = new Intent();
        intent.setPackage(context.getPackageName());
        intent.setAction(CIMConstant.IntentAction.Companion.getACTION_CONNECTION_CLOSED());
        context.sendBroadcast(intent);

    }

    private void sessionIdle() {
        LOGGER.sessionIdle(socketChannel);
        /*用于解决，wifi情况下。偶而路由器与服务器断开连接时，客户端并没及时收到关闭事件，导致这样的情况下当前连接无效也不会重连的问题*/
        if (System.currentTimeMillis() - LAST_READ_TIME.get() >= CONNECT_ALIVE_TIME_OUT) {
            closeSession();
        }
    }

    private void messageReceived(Object obj) {
        if (obj instanceof Message) {
            Intent intent = new Intent();
            intent.setPackage(context.getPackageName());
            intent.setAction(CIMConstant.IntentAction.Companion.getACTION_MESSAGE_RECEIVED());
            intent.putExtra(Message.class.getName(), (Message) obj);
            context.sendBroadcast(intent);
        }
        if (obj instanceof ReplyBody) {
            Intent intent = new Intent();
            intent.setPackage(context.getPackageName());
            intent.setAction(CIMConstant.IntentAction.Companion.getACTION_REPLY_RECEIVED());
            intent.putExtra(ReplyBody.class.getName(), (ReplyBody) obj);
            context.sendBroadcast(intent);
        }
    }

    private void messageSent(Object message) {
        LOGGER.messageSent(socketChannel, message);
        if (message instanceof SentBody) {
            Intent intent = new Intent();
            intent.setPackage(context.getPackageName());
            intent.setAction(CIMConstant.IntentAction.Companion.getACTION_SENT_SUCCESSED());
            intent.putExtra(SentBody.class.getName(), (SentBody) message);
            context.sendBroadcast(intent);
        }
    }

    private Handler idleHandler = new Handler(IDLE_HANDLER_THREAD.getLooper()) {
        @Override
        public void handleMessage(android.os.Message m) {
            sessionIdle();
        }
    };

    private void handelDisconnectedEvent() {
        closeSession();
    }

    private void handleConnectAbortedEvent() {
        long interval = CIMConstant.Companion.getRECONN_INTERVAL_TIME() - (5 * 1000 - new Random().nextInt(15 * 1000));
        LOGGER.connectFailure(interval);
        Intent intent = new Intent();
        intent.setPackage(context.getPackageName());
        intent.setAction(CIMConstant.IntentAction.Companion.getACTION_CONNECTION_FAILED());
        intent.putExtra("interval", interval);
        context.sendBroadcast(intent);
    }

    private void handelConnectedEvent() {
        sessionCreated();
        idleHandler.sendEmptyMessageDelayed(0, READ_IDLE_TIME);
    }

    private void handelSocketReadEvent(int result) throws IOException {
        if (result == -1) {
            closeSession();
            return;
        }
        markLastReadTime();
        readBuffer.position(0);
        Object message = messageDecoder.doDecode(readBuffer);
        if (message == null) {
            return;
        }
        LOGGER.messageReceived(socketChannel, message);
        if (isHeartbeatRequest(message)) {
            send(getHeartbeatResponse());
            return;
        }
        this.messageReceived(message);
    }

    private void extendByteBuffer() {
        ByteBuffer newBuffer = ByteBuffer.allocate(readBuffer.capacity() + READ_BUFFER_SIZE / 2);
        readBuffer.position(0);
        newBuffer.put(readBuffer);
        readBuffer.clear();
        readBuffer = newBuffer;
    }

    private void markLastReadTime() {
        LAST_READ_TIME.set(System.currentTimeMillis());
        idleHandler.removeMessages(0);
        idleHandler.sendEmptyMessageDelayed(0, READ_IDLE_TIME);
    }

    private HeartbeatResponse getHeartbeatResponse() {
        return HeartbeatResponse.getInstance();
    }

    private boolean isHeartbeatRequest(Object data) {
        return data instanceof HeartbeatRequest;
    }


}
