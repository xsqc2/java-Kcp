import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import test.Player;
import test.tcp.NetAcceptor;
import test.tcp.TcpChannelInitializer;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ChannelHandler.Sharable
public class TcpChatServer extends SimpleChannelInboundHandler<ByteBuf> {

    Map<Integer, Player> IdMapToPlayer= new ConcurrentHashMap<>();
    Map<Integer, SocketAddress> IdMapToAddress= new ConcurrentHashMap<>();

//    private static final RoomManager roomManager = new RoomManager();
    public static void main(String[] args) {
        TcpChatServer tcpChatServer = new TcpChatServer();
        TcpChannelInitializer tcpChannelInitializer = new TcpChannelInitializer(tcpChatServer);
        new NetAcceptor(tcpChannelInitializer,new InetSocketAddress(11009));
        System.out.println("============== 聊天室已开启（KCP） ==============");
    }

    public static final AttributeKey<Player> playerAttributeKey = AttributeKey.newInstance("player");

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        String msg = ctx.channel().remoteAddress().toString()+"进入聊天室";
        System.out.println(msg);
        Player player = new Player(byteBuf -> ctx.channel().writeAndFlush(byteBuf));
        Attribute<Player> playerAttribute = ctx.channel().attr(playerAttributeKey);
        playerAttribute.set(player);
//        roomManager.joinRoom(player);
        for(Player p : IdMapToPlayer.values()){
            if(p.getId() != player.getId()){
                p.write(Unpooled.copiedBuffer(msg, CharsetUtil.UTF_8));
            }
        };

        IdMapToPlayer.put(player.getId(), player);
        IdMapToAddress.put(player.getId(), ctx.channel().remoteAddress());
    }


    private Player getPlayer(ChannelHandlerContext ctx){
        Attribute<Player> playerAttribute = ctx.channel().attr(playerAttributeKey);
        return playerAttribute.get();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Player player = getPlayer(ctx);
//        roomManager.remove(player.getId());
        String msg = ctx.channel().remoteAddress().toString()+"离开聊天室";
        System.out.println(msg);

        IdMapToPlayer.remove(player.getId());
        for(Player p : IdMapToPlayer.values()){
            p.write(Unpooled.copiedBuffer(msg, CharsetUtil.UTF_8));
        };
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf byteBuf) throws Exception {
        Player player = getPlayer(ctx);
//        Room room = roomManager.getRoom(player.getId());
//        ByteBuf byteBuf1 = ByteBufAllocator.DEFAULT.directBuffer(20);
//        byteBuf.readBytes(byteBuf1);
//        byteBuf1.readerIndex(0);
//        byteBuf1.writerIndex(20);
//        room.getiMessageExecutor().execute(() ->{
//                    player.getMessages().add(byteBuf1);
//                }
//        );
        
        for(Player p : IdMapToPlayer.values()){
            String msg = byteBuf.toString(CharsetUtil.UTF_8).trim();
            if(p.getId() == player.getId()){
                msg = "[you]: "+msg;
            } else {
                msg = "[" + IdMapToAddress.get(p.getId()).toString() +" (id: "+p.getId()+")]: "+msg;
            }
            p.write(Unpooled.copiedBuffer(msg, CharsetUtil.UTF_8));
        };
    }
}
