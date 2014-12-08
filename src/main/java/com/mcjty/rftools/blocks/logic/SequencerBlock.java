package com.mcjty.rftools.blocks.logic;

import com.mcjty.container.EmptyContainer;
import com.mcjty.rftools.RFTools;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.material.Material;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumChatFormatting;

import java.util.List;

public class SequencerBlock extends LogicSlabBlock {

    public SequencerBlock() {
        super(Material.iron, "sequencerBlock", SequencerTileEntity.class);
        setCreativeTab(RFTools.tabRfTools);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack itemStack, EntityPlayer player, List list, boolean whatIsThis) {
        super.addInformation(itemStack, player, list, whatIsThis);
        NBTTagCompound tagCompound = itemStack.getTagCompound();
        if (tagCompound != null) {
            int delay = tagCompound.getInteger("delay");
            list.add(EnumChatFormatting.GREEN + "Delay: " + delay);
            long cycleBits = tagCompound.getLong("bits");

            int mode = tagCompound.getInteger("mode");
            String smode = SequencerMode.values()[mode].getDescription();
            list.add(EnumChatFormatting.GREEN + "Mode: " + smode);

            list.add(EnumChatFormatting.GREEN + "Bits: " + Long.toHexString(cycleBits));
        }
    }

    @Override
    public int getGuiID() {
        return RFTools.GUI_SEQUENCER;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public GuiContainer createClientGui(EntityPlayer entityPlayer, TileEntity tileEntity) {
        SequencerTileEntity sequencerTileEntity = (SequencerTileEntity) tileEntity;
        return new GuiSequencer(sequencerTileEntity, new EmptyContainer(entityPlayer));
    }

    @Override
    public String getIdentifyingIconName() {
        return "machineSequencerTop";
    }
}
