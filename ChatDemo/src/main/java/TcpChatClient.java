import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.CharsetUtil;
import test.tcp.NetConnector;
import test.tcp.TcpChannelInitializer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;

@ChannelHandler.Sharable
public class TcpChatClient extends SimpleChannelInboundHandler<ByteBuf> {

    public static void main(String[] args) throws IOException {
        TcpChatClient tcpChatClient = new TcpChatClient();
        NetConnector netConnector = new NetConnector(new TcpChannelInitializer(tcpChatClient));
        Channel server = null;
        try {
            server = netConnector.connect(new InetSocketAddress("127.0.0.1",11009));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        while (true) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in));
            String input = reader.readLine();
            if (input != null) {
                if ("quit".equals(input)) {
                    System.exit(1);
                }
                assert server != null;
                server.writeAndFlush(Unpooled.copiedBuffer(input, CharsetUtil.UTF_8));
            }
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        System.out.println("（已进入聊天室。）");
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        System.out.println("（已离开聊天室。）");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        System.out.println("（已离开聊天室。）");
        cause.printStackTrace();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf) throws Exception {
        String msg = byteBuf.toString(CharsetUtil.UTF_8).trim();
        System.out.println(msg);
    }
}
