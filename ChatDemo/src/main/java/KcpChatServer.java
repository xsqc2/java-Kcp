import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import kcp.*;

public class KcpChatServer implements KcpListener {
    KcpServer kcpServer;
    public static void main(String[] args) {
        ChannelConfig channelConfig = new ChannelConfig();
        channelConfig.nodelay(true,40,2,true);
        channelConfig.setSndwnd(512);
        channelConfig.setRcvwnd(512);
        channelConfig.setMtu(512);
//        channelConfig.setFecDataShardCount(3);
//        channelConfig.setFecParityShardCount(1);
        channelConfig.setAckNoDelay(true);
        channelConfig.setTimeoutMillis(0);
        channelConfig.setUseConvChannel(true);
//        channelConfig.setCrc32Check(true);
        KcpServer kcpServer = new KcpServer();
        KcpChatServer kcpChatServer = new KcpChatServer();
        kcpChatServer.kcpServer = kcpServer;
        kcpServer.init(kcpChatServer,channelConfig,20003);
        System.out.println("============== 聊天室已开启（KCP） ==============");
    }

    @Override
    public void onConnected(Ukcp ukcp) {
        String msg = "[" + ukcp.user().getRemoteAddress() +" (conv: "+ ukcp.getConv()+")] 进入聊天室。";
        for(Ukcp kcp : kcpServer.getChannelManager().getAll()){
            if(kcp.getConv() != ukcp.getConv()){
                kcp.write(Unpooled.copiedBuffer(msg, CharsetUtil.UTF_8));
            }
        };
        System.out.println(msg);
    }

    @Override
    public void handleReceive(ByteBuf byteBuf, Ukcp ukcp) {
        if(byteBuf.toString(CharsetUtil.UTF_8).equals(" ")){
            return;
        }
        for(Ukcp kcp : kcpServer.getChannelManager().getAll()){
            String msg = byteBuf.toString(CharsetUtil.UTF_8);
            if(kcp.getConv() == ukcp.getConv()){
                msg = "[you]: "+msg;
            } else {
                msg = "[" + ukcp.user().getRemoteAddress() +" (conv: "+ ukcp.getConv()+")]: "+msg;
            }
            kcp.write(Unpooled.copiedBuffer(msg, CharsetUtil.UTF_8));
        };
    }

    @Override
    public void handleException(Throwable ex, Ukcp ukcp) {
        String msg = "[" + ukcp.user().getRemoteAddress() +" (conv: "+ ukcp.getConv()+")] 离开聊天室。";
        for(Ukcp kcp : kcpServer.getChannelManager().getAll()){
            if(kcp.getConv() != ukcp.getConv()){
                kcp.write(Unpooled.copiedBuffer(msg, CharsetUtil.UTF_8));
            }
        };
        System.out.println(msg);
    }

    @Override
    public void handleClose(Ukcp ukcp) {
        String msg ="[" + ukcp.user().getRemoteAddress() +" (conv: "+ ukcp.getConv()+")] 离开聊天室。";
        for(Ukcp kcp : kcpServer.getChannelManager().getAll()){
            if(kcp.getConv() != ukcp.getConv()){
                kcp.write(Unpooled.copiedBuffer(msg, CharsetUtil.UTF_8));
            }
        };
        System.out.println(msg);
    }
}
