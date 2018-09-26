package kcp;

import com.backblaze.erasure.ReedSolomon;
import com.backblaze.erasure.fec.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.jctools.queues.MpscArrayQueue;
import org.jctools.queues.SpscLinkedQueue;
import threadPool.task.ITask;
import threadPool.thread.DisruptorExecutorPool;
import threadPool.thread.IMessageExecutor;

import java.io.IOException;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wrapper for kcp
 *
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public class Ukcp implements Runnable, ITask {

    private static final InternalLogger log = InternalLoggerFactory.getInstance(Ukcp.class);

    private Kcp kcp;

    private boolean fastFlush = false;

    private long tsUpdate = -1;

    private volatile boolean active;

    private FecEncode fecEncode = null;
    private FecDecode fecDecode = null;

    private final MpscArrayQueue<ByteBuf> sendList;

    private final Queue<ByteBuf> recieveList;

    private IMessageExecutor disruptorSingleExecutor;

    private KcpListener kcpListener;

    /**
     * 上次收到消息时间
     **/
    private long lastRecieveTime = System.currentTimeMillis();

    /**
     * 超时关闭时间
     **/
    private long closeTime = 0;

    /**
     * 是否执行完了
     **/
    private AtomicBoolean isExecute = new AtomicBoolean(true);

    private ScheduledFuture<?> scheduledFuture;


    /**
     * Creates a new instance.
     *
     * @param conv   conv of kcp
     * @param output output for kcp
     */
    public Ukcp(int conv, KcpOutput output, KcpListener kcpListener, IMessageExecutor disruptorSingleExecutor) {
        Kcp kcp = new Kcp(conv, output);
        this.kcp = kcp;
        this.active = true;
        this.kcpListener = kcpListener;
        this.disruptorSingleExecutor = disruptorSingleExecutor;
        //默认2<<16   可以修改
        sendList = new MpscArrayQueue<>(2 << 16);
        recieveList = new SpscLinkedQueue<>();
        this.scheduledFuture = DisruptorExecutorPool.schedule(this, kcp.getInterval());
    }


    public void initFec(ReedSolomon reedSolomon) {
        //init fec
        if (reedSolomon != null) {
            KcpOutput kcpOutput = kcp.getOutput();
            fecEncode = new FecEncode(0, reedSolomon);
            fecDecode = new FecDecode(3 * reedSolomon.getTotalShardCount(), reedSolomon);
            kcpOutput = new FecOutPut1(kcpOutput, fecEncode);
            kcp.setOutput(kcpOutput);
        }
    }

    /**
     * Receives ByteBufs.
     *
     * @param bufList received ByteBuf will be add to the list
     */
    protected void receive(List<ByteBuf> bufList) {
        kcp.recv(bufList);
    }

    public void input(ByteBuf data) throws IOException {
        lastRecieveTime = System.currentTimeMillis();
        Snmp.snmp.InPkts.incrementAndGet();
        Snmp.snmp.InBytes.addAndGet(data.readableBytes());

        if (fecDecode != null) {
            FecPacket fecPacket = FecPacket.newFecPacket(data);
            if (fecPacket.getFlag() == Fec.typeData) {
                data.skipBytes(2);
                input(data, true);
                //data.release();
            }
            if (fecPacket.getFlag() == Fec.typeData || fecPacket.getFlag() == Fec.typeFEC) {
                List<ByteBuf> byteBufs = fecDecode.decode(fecPacket);
                if (byteBufs != null) {
                    for (ByteBuf byteBuf : byteBufs) {
                        input(byteBuf, false);
                        byteBuf.release();
                    }
                }
            }
        } else {
            input(data, true);
        }
    }

    private void input(ByteBuf data, boolean regular) throws IOException {
        int ret = kcp.input(data, regular);
        switch (ret) {
            case -1:
                throw new IOException("No enough bytes of head");
            case -2:
                throw new IOException("No enough bytes of data");
            case -3:
                throw new IOException("Mismatch cmd");
            case -4:
                throw new IOException("Conv inconsistency");
            default:
                break;
        }
    }


    /**
     * Sends a Bytebuf.
     *
     * @param buf
     * @throws IOException
     */
    void send(ByteBuf buf) throws IOException {
        int ret = kcp.send(buf);
        switch (ret) {
            case -2:
                throw new IOException("Too many fragments");
            default:
                break;
        }
    }

    /**
     * The size of the first msg of the kcp.
     *
     * @return The size of the first msg of the kcp, or -1 if none of msg
     */
    public int peekSize() {
        return kcp.peekSize();
    }

    /**
     * Returns {@code true} if there are bytes can be received.
     *
     * @return
     */
    public boolean canRecv() {
        return kcp.canRecv();
    }


    public long getLastRecieveTime() {
        return lastRecieveTime;
    }


    /**
     * Returns {@code true} if the kcp can send more bytes.
     *
     * @param curCanSend last state of canSend
     * @return {@code true} if the kcp can send more bytes
     */
    protected boolean canSend(boolean curCanSend) {
        int max = kcp.getSndWnd() * 2;
        int waitSnd = kcp.waitSnd();
        if (curCanSend) {
            return waitSnd < max;
        } else {
            int threshold = Math.max(1, max / 2);
            return waitSnd < threshold;
        }
    }

    /**
     * Udpates the kcp.
     *
     * @param current current time in milliseconds
     * @return the next time to update
     */
    public long update(long current) {
        kcp.update(current);
        long nextTsUp = check(current);
        setTsUpdate(nextTsUp);

        return nextTsUp;
    }

    /**
     * Determines when should you invoke udpate.
     *
     * @param current current time in milliseconds
     * @return
     * @see Kcp#check(long)
     */
    public long check(long current) {
        return kcp.check(current);
    }

    /**
     * Returns {@code true} if the kcp need to flush.
     *
     * @return {@code true} if the kcp need to flush
     */
    public boolean checkFlush() {
        return kcp.checkFlush();
    }

    /**
     * Sets params of nodelay.
     *
     * @param nodelay  {@code true} if nodelay mode is enabled
     * @param interval protocol internal work interval, in milliseconds
     * @param resend   fast retransmission mode, 0 represents off by default, 2 can be set (2 ACK spans will result
     *                 in direct retransmission)
     * @param nc       {@code true} if turn off flow control
     */
    public void nodelay(boolean nodelay, int interval, int resend, boolean nc) {
        kcp.nodelay(nodelay, interval, resend, nc);
    }

    /**
     * Returns conv of kcp.
     *
     * @return conv of kcp
     */
    public int getConv() {
        return kcp.getConv();
    }

    /**
     * Set the conv of kcp.
     *
     * @param conv the conv of kcp
     */
    public void setConv(int conv) {
        kcp.setConv(conv);
    }

    /**
     * Returns {@code true} if and only if nodelay is enabled.
     *
     * @return {@code true} if and only if nodelay is enabled
     */
    public boolean isNodelay() {
        return kcp.isNodelay();
    }

    /**
     * Sets whether enable nodelay.
     *
     * @param nodelay {@code true} if enable nodelay
     * @return this object
     */
    public Ukcp setNodelay(boolean nodelay) {
        kcp.setNodelay(nodelay);
        return this;
    }

    /**
     * Returns update interval.
     *
     * @return update interval
     */
    public int getInterval() {
        return kcp.getInterval();
    }

    /**
     * Sets update interval
     *
     * @param interval update interval
     * @return this object
     */
    public Ukcp setInterval(int interval) {
        kcp.setInterval(interval);
        return this;
    }

    /**
     * Returns the fastresend of kcp.
     *
     * @return the fastresend of kcp
     */
    public int getFastResend() {
        return kcp.getFastresend();
    }

    /**
     * Sets the fastresend of kcp.
     *
     * @param fastResend
     * @return this object
     */
    public Ukcp setFastResend(int fastResend) {
        kcp.setFastresend(fastResend);
        return this;
    }

    public boolean isNocwnd() {
        return kcp.isNocwnd();
    }

    public Ukcp setNocwnd(boolean nocwnd) {
        kcp.setNocwnd(nocwnd);
        return this;
    }

    public int getMinRto() {
        return kcp.getRxMinrto();
    }

    public Ukcp setMinRto(int minRto) {
        kcp.setRxMinrto(minRto);
        return this;
    }

    public int getMtu() {
        return kcp.getMtu();
    }

    public Ukcp setMtu(int mtu) {
        kcp.setMtu(mtu);
        return this;
    }

    public boolean isStream() {
        return kcp.isStream();
    }

    public Ukcp setStream(boolean stream) {
        kcp.setStream(stream);
        return this;
    }

    public int getDeadLink() {
        return kcp.getDeadLink();
    }

    public Ukcp setDeadLink(int deadLink) {
        kcp.setDeadLink(deadLink);
        return this;
    }

    /**
     * Sets the {@link ByteBufAllocator} which is used for the kcp to allocate buffers.
     *
     * @param allocator the allocator is used for the kcp to allocate buffers
     * @return this object
     */
    public Ukcp setByteBufAllocator(ByteBufAllocator allocator) {
        kcp.setByteBufAllocator(allocator);
        return this;
    }

    public boolean isAutoSetConv() {
        return kcp.isAutoSetConv();
    }

    public Ukcp setAutoSetConv(boolean autoSetConv) {
        kcp.setAutoSetConv(autoSetConv);
        return this;
    }

    public Ukcp wndSize(int sndWnd, int rcvWnd) {
        kcp.wndsize(sndWnd, rcvWnd);
        return this;
    }

    public int waitSnd() {
        return kcp.waitSnd();
    }

    public int getRcvWnd() {
        return kcp.getRcvWnd();
    }

    public Ukcp setRcvWnd(int rcvWnd) {
        kcp.setRcvWnd(rcvWnd);
        return this;
    }

    public int getSndWnd() {
        return kcp.getSndWnd();
    }

    public Ukcp setSndWnd(int sndWnd) {
        kcp.setSndWnd(sndWnd);
        return this;
    }

    public boolean isFastFlush() {
        return fastFlush;
    }

    public Ukcp setFastFlush(boolean fastFlush) {
        this.fastFlush = fastFlush;
        return this;
    }


    public boolean isAckNoDelay() {
        return this.kcp.isAckNoDelay();
    }

    public Ukcp setAckNoDelay(boolean ackNoDelay) {
        this.kcp.setAckNoDelay(ackNoDelay);
        return this;
    }


    public void read(ByteBuf byteBuf) {
        //System.out.println("recieve "+Thread.currentThread().getName());
        this.recieveList.add(byteBuf);
        RecieveTask recieveTask = RecieveTask.newRecieveTask(this);
        this.disruptorSingleExecutor.execute(recieveTask);
    }

    public boolean write(ByteBuf byteBuf) {
        if (!sendList.offer(byteBuf)) {
            return false;
        }
        triggerWriteEvent();
        return true;
    }

    private void triggerWriteEvent() {
        SendTask sendTask = SendTask.newSendTask(this);
        this.disruptorSingleExecutor.execute(sendTask);
    }


    public long getTsUpdate() {
        return tsUpdate;
    }

    public Queue<ByteBuf> getRecieveList() {
        return recieveList;
    }

    public Ukcp setTsUpdate(long tsUpdate) {
        this.tsUpdate = tsUpdate;
        return this;
    }

    public int getState() {
        return kcp.getState();
    }


    public MpscArrayQueue<ByteBuf> getSendList() {
        return sendList;
    }

    public KcpListener getKcpListener() {
        return kcpListener;
    }

    public boolean isActive() {
        return active;
    }

    void setClosed(boolean closeKcp) {
        kcpListener.handleClose(this);
        this.active = false;
        if (closeKcp) {
            setKcpClosed();
        }
    }

    void setKcpClosed() {
        kcp.setState(-1);
        kcp.release();
        for (; ; ) {
            ByteBuf byteBuf = sendList.poll();
            if (byteBuf == null)
                break;
            byteBuf.release();
        }
        for (; ; ) {
            ByteBuf byteBuf = recieveList.poll();
            if (byteBuf == null)
                break;
            byteBuf.release();
        }
        if (this.fecEncode != null) {
            this.fecEncode.release();
        }

        if (this.fecDecode != null) {
            this.fecDecode.release();
        }
    }

    public long getCloseTime() {
        return closeTime;
    }

    public void setCloseTime(long closeTime) {
        this.closeTime = closeTime;
    }

    @SuppressWarnings("unchecked")
    public <T extends Channel> T channel() {
        return (T) kcp.getUser();
    }

    public Ukcp channel(Channel channel) {
        kcp.setUser(channel);
        return this;
    }

    @Override
    public String toString() {
        return "Ukcp(" +
                "getConv=" + kcp.getConv() +
                ", state=" + kcp.getState() +
                ", active=" + active +
                ')';
    }

    @Override
    public void run() {
        isExecute.set(false);
        this.disruptorSingleExecutor.execute(this);
    }

    @Override
    public void execute() {
        try {
            long now = System.currentTimeMillis();
            //判断连接是否关闭
            if (this.closeTime != 0 && now + this.closeTime > this.lastRecieveTime) {
                setClosed(true);
                //release();
                return;
            }
            if (!this.isActive())
                return;
            //检查flush
            long next;
            if (this.tsUpdate == -1) {
                next = update(now);
                scheduledFuture = DisruptorExecutorPool.schedule(this, next - now);
                return;
            }
            next = check(now);
            if (now >= next) {
                next = update(now);
            } else {
                setTsUpdate(next);
            }
            scheduledFuture = DisruptorExecutorPool.schedule(this, next - now);
            //TODO 检测写缓冲区 如果能写则触发写事件
            //if(canSend(false)){
            //    triggerWriteEvent();
            //}

        } catch (Exception e) {

            e.printStackTrace();
        }
    }
}