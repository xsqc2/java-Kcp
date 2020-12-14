import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.UUID;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.CharsetUtil;
import kcp.*;

public class ChatClient implements KcpListener {

    private boolean stop = false;

    public static void main(String[] args) throws IOException {
        ChatClient chatClient = new ChatClient();

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
        Ukcp server = kcpClient.connect(new InetSocketAddress("127.0.0.1",20003),channelConfig, chatClient);
        server.write(Unpooled.copiedBuffer(" ", CharsetUtil.UTF_8));
        while (true) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in));
            String input = reader.readLine();
            if (input != null) {
                if ("quit".equals(input)) {
                    System.exit(1);
                }
                server.write(Unpooled.copiedBuffer(input, CharsetUtil.UTF_8));
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
