package mcjty.rftools.blocks.shaper;

import mcjty.lib.tools.ItemStackTools;
import mcjty.rftools.shapes.ShapeRenderer;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class ProjectorRenderer extends TileEntitySpecialRenderer<ProjectorTileEntity> {

    @Override
    public void renderTileEntityAt(ProjectorTileEntity te, double x, double y, double z, float partialTicks, int destroyStage) {
        super.renderTileEntityAt(te, x, y, z, partialTicks, destroyStage);

        ItemStack renderStack = te.getRenderStack();
        if (ItemStackTools.isValid(renderStack)) {
            ShapeRenderer renderer = te.getShapeRenderer();
            renderer.renderShapeInWorld(renderStack, x, y, z);
        }
    }
}