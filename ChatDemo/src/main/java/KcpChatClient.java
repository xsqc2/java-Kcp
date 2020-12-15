import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import kcp.*;

public class KcpChatClient implements KcpListener {

    private boolean stop = false;

    public static void main(String[] args) throws IOException {
        KcpChatClient kcpChatClient = new KcpChatClient();

        ChannelConfig channelConfig = new ChannelConfig();
        channelConfig.nodelay(true,40,2,true);
        channelConfig.setConv((int)(Math.random()*1000));
        channelConfig.setSndwnd(512);
        channelConfig.setRcvwnd(512);
        channelConfig.setMtu(512);
//        channelConfig.setFecDataShardCount(3);
//        channelConfig.setFecParityShardCount(1);
        channelConfig.setAckNoDelay(true);
        channelConfig.setTimeoutMillis(0);
        channelConfig.setUseConvChannel(true);
//        channelConfig.setCrc32Check(true);
        KcpClient kcpClient = new KcpClient();
        kcpClient.init(channelConfig);
        Ukcp server = kcpClient.connect(new InetSocketAddress("127.0.0.1",20003),channelConfig, kcpChatClient);
        server.write(Unpooled.copiedBuffer(" ", CharsetUtil.UTF_8));
        while (true) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in));
            String input = reader.readLine();
            if (input != null) {
                server.write(Unpooled.copiedBuffer(input, CharsetUtil.UTF_8));
                if ("quit".equals(input)) {
                    System.exit(1);
                }
            }
        }
    }

    @Override
    public void onConnected(Ukcp ukcp) {
        System.out.println("（已进入聊天室。）");
    }

    @Override
    public void handleReceive(ByteBuf byteBuf, Ukcp ukcp) {
        String msg = byteBuf.toString(CharsetUtil.UTF_8);
        System.out.println(msg);
    }

    @Override
    public void handleException(Throwable ex, Ukcp ukcp) {
        System.out.println("（已离开聊天室。）");
    }

    @Override
    public void handleClose(Ukcp ukcp) {
        System.out.println("（已离开聊天室。）");
    }
}
