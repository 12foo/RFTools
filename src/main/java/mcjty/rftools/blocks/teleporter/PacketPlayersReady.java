package mcjty.rftools.blocks.teleporter;

import io.netty.buffer.ByteBuf;
import mcjty.lib.network.ClientCommandHandler;
import mcjty.lib.network.PacketListFromServer;
import mcjty.lib.varia.Logging;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.List;

public class PacketPlayersReady extends PacketListFromServer<PacketPlayersReady,PlayerName> {

    public PacketPlayersReady() {
    }

    public PacketPlayersReady(BlockPos pos, String command, List<PlayerName> list) {
        super(pos, command, list);
    }

    public class Handler implements IMessageHandler<PacketPlayersReady, IMessage> {
        @Override
        public IMessage onMessage(PacketPlayersReady message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> handle(message, ctx));
            return null;
        }

        private void handle(PacketPlayersReady message, MessageContext ctx) {
            TileEntity te = Minecraft.getMinecraft().theWorld.getTileEntity(message.pos);
            if(!(te instanceof ClientCommandHandler)) {
                Logging.log("createInventoryReadyPacket: TileEntity is not a ClientCommandHandler!");
                return;
            }
            ClientCommandHandler clientCommandHandler = (ClientCommandHandler) te;
            if (!clientCommandHandler.execute(message.command, message.list)) {
                Logging.log("Command " + message.command + " was not handled!");
            }
        }
    }
    @Override
    protected PlayerName createItem(ByteBuf buf) {
        return new PlayerName(buf);
    }
}