package mcjty.rftools.blocks.builder;

import com.mojang.authlib.GameProfile;
import mcjty.lib.container.DefaultSidedInventory;
import mcjty.lib.container.InventoryHelper;
import mcjty.lib.entity.GenericEnergyReceiverTileEntity;
import mcjty.lib.network.Argument;
import mcjty.lib.network.PacketRequestIntegerFromServer;
import mcjty.lib.tools.InventoryTools;
import mcjty.lib.tools.ItemStackTools;
import mcjty.lib.varia.*;
import mcjty.rftools.RFTools;
import mcjty.rftools.blocks.teleporter.TeleportationTools;
import mcjty.rftools.hud.IHudSupport;
import mcjty.rftools.shapes.Shape;
import mcjty.rftools.items.builder.ShapeCardItem;
import mcjty.rftools.items.storage.StorageFilterCache;
import mcjty.rftools.items.storage.StorageFilterItem;
import mcjty.rftools.network.PacketGetHudLog;
import mcjty.rftools.network.RFToolsMessages;
import mcjty.rftools.proxy.CommonProxy;
import mcjty.rftools.varia.RFToolsTools;
import mcjty.typed.Type;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.*;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fluids.BlockFluidBase;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

public class BuilderTileEntity extends GenericEnergyReceiverTileEntity implements DefaultSidedInventory, ITickable,
        IHudSupport {

    public static final String COMPONENT_NAME = "builder";

    public static final String CMD_SETMODE = "setMode";
    public static final String CMD_SETANCHOR = "setAnchor";
    public static final String CMD_SETROTATE = "setRotate";
    public static final String CMD_SETSILENT = "setSilent";
    public static final String CMD_SETSUPPORT = "setSupport";
    public static final String CMD_SETENTITIES = "setEntities";
    public static final String CMD_SETLOOP = "setLoop";
    public static final String CMD_GETLEVEL = "getLevel";
    public static final String CMD_MODE = "setMode";
    public static final String CMD_RESTART = "restart";
    public static final String CLIENTCMD_GETLEVEL = "getLevel";

    private InventoryHelper inventoryHelper = new InventoryHelper(this, BuilderContainer.factory, 2);

    public static final int MODE_COPY = 0;
    public static final int MODE_MOVE = 1;
    public static final int MODE_SWAP = 2;
    public static final int MODE_BACK = 3;
    public static final int MODE_COLLECT = 4;

    public static final String[] MODES = new String[]{"Copy", "Move", "Swap", "Back", "Collect"};

    public static final String ROTATE_0 = "0";
    public static final String ROTATE_90 = "90";
    public static final String ROTATE_180 = "180";
    public static final String ROTATE_270 = "270";

    public static final int ANCHOR_SW = 0;
    public static final int ANCHOR_SE = 1;
    public static final int ANCHOR_NW = 2;
    public static final int ANCHOR_NE = 3;

    private int mode = MODE_COPY;
    private int rotate = 0;
    private int anchor = ANCHOR_SW;
    private boolean silent = false;
    private boolean supportMode = false;
    private boolean entityMode = false;
    private boolean loopMode = false;

    // For usage in the gui
    private static int currentLevel = 0;

    private int collectCounter = BuilderConfiguration.collectTimer;
    private int collectXP = 0;

    private boolean boxValid = false;
    private BlockPos minBox = null;
    private BlockPos maxBox = null;
    private BlockPos scan = null;
    private int projDx;
    private int projDy;
    private int projDz;

    private long lastHudTime = 0;
    private List<String> clientHudLog = new ArrayList<>();

    private int cardType = ShapeCardItem.CARD_UNKNOWN; // One of the card types out of ShapeCardItem.CARD_...

    private StorageFilterCache filterCache = null;

    // For chunkloading with the quarry.
    private ForgeChunkManager.Ticket ticket = null;
    // The currently forced chunk.
    private ChunkPos forcedChunk = null;

    // Cached set of blocks that we need to build in shaped mode
    private Map<BlockPos, IBlockState> cachedBlocks = null;
    private ChunkPos cachedChunk = null;       // For which chunk are the cachedBlocks valid

    // Cached set of blocks that we want to void with the quarry.
    private Set<Block> cachedVoidableBlocks = null;

    private static FakePlayer harvester = null;

    public BuilderTileEntity() {
        super(BuilderConfiguration.BUILDER_MAXENERGY, BuilderConfiguration.BUILDER_RECEIVEPERTICK);
        setRSMode(RedstoneMode.REDSTONE_ONREQUIRED);
    }

    @Override
    protected boolean needsRedstoneMode() {
        return true;
    }

    @Override
    protected boolean needsCustomInvWrapper() {
        return true;
    }

    private static FakePlayer getHarvester() {
        if (harvester == null) {
            harvester = FakePlayerFactory.get(DimensionManager.getWorld(0), new GameProfile(new UUID(111, 333), "rftools_builder"));
        }
        return harvester;
    }

    @Override
    public EnumFacing getBlockOrientation() {
        return BlockTools.getOrientationHoriz(getBlockMetadata());
    }

    @Override
    public boolean isBlockAboveAir() {
        return getWorld().isAirBlock(pos.up());
    }

    @Override
    public List<String> getClientLog() {
        return clientHudLog;
    }

    public List<String> getHudLog() {
        List<String> list = new ArrayList<>();
        list.add(TextFormatting.BLUE + "Mode:");
        if (isShapeCard()) {
            switch (getCardType()) {
                case ShapeCardItem.CARD_VOID:
                    list.add("    Void mode");
                    break;
                case ShapeCardItem.CARD_PUMP:
                    list.add("    Pump");
                    break;
                case ShapeCardItem.CARD_PUMP_LIQUID:
                    list.add("    Place liquids");
                    break;
                case ShapeCardItem.CARD_PUMP_CLEAR:
                    list.add("    Pump");
                    list.add("    (clearing)");
                    break;
                case ShapeCardItem.CARD_QUARRY_FORTUNE:
                    list.add("    Fortune quarry");
                    break;
                case ShapeCardItem.CARD_QUARRY_CLEAR_FORTUNE:
                    list.add("    Fortune quarry");
                    list.add("    (clearing)");
                    break;
                case ShapeCardItem.CARD_QUARRY_SILK:
                    list.add("    Silktouch quarry");
                    break;
                case ShapeCardItem.CARD_QUARRY_CLEAR_SILK:
                    list.add("    Silktouch quarry");
                    list.add("    (clearing)");
                    break;
                case ShapeCardItem.CARD_QUARRY:
                    list.add("    Normal quarry");
                    break;
                case ShapeCardItem.CARD_QUARRY_CLEAR:
                    list.add("    Normal quarry");
                    list.add("    (clearing)");
                    break;
                case ShapeCardItem.CARD_SHAPE:
                    list.add("    Shape card");
                    ItemStack shapeCard = inventoryHelper.getStackInSlot(BuilderContainer.SLOT_TAB);
                    if (ItemStackTools.isValid(shapeCard)) {
                        Shape shape = ShapeCardItem.getShape(shapeCard);
                        if (shape != null) {
                            list.add("    " + shape.getDescription());
                        }
                    }
                    break;
            }
        } else {
            list.add("    Space card: " + new String[]{"copy", "move", "swap", "back", "collect"}[mode]);
        }
        if (scan != null) {
            list.add(TextFormatting.BLUE + "Progress:");
            list.add("    Y level: " + scan.getY());
            int minChunkX = minBox.getX() >> 4;
            int minChunkZ = minBox.getZ() >> 4;
            int maxChunkX = maxBox.getX() >> 4;
            int maxChunkZ = maxBox.getZ() >> 4;
            int curX = scan.getX() >> 4;
            int curZ = scan.getZ() >> 4;
            int totChunks = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
            int curChunk = (curZ - minChunkZ) * (maxChunkX - minChunkX) + curX - minChunkX;
            list.add("    Chunk:  " + curChunk + " of " + totChunks);
        }
        return list;
    }

    @Override
    public BlockPos getBlockPos() {
        return getPos();
    }

    @Override
    public long getLastUpdateTime() {
        return lastHudTime;
    }

    @Override
    public void setLastUpdateTime(long t) {
        lastHudTime = t;
    }

    private boolean isShapeCard() {
        ItemStack itemStack = inventoryHelper.getStackInSlot(BuilderContainer.SLOT_TAB);
        if (ItemStackTools.isEmpty(itemStack)) {
            return false;
        }
        return itemStack.getItem() == BuilderSetup.shapeCardItem;
    }

    private NBTTagCompound hasCard() {
        ItemStack itemStack = inventoryHelper.getStackInSlot(BuilderContainer.SLOT_TAB);
        if (ItemStackTools.isEmpty(itemStack)) {
            return null;
        }

        return itemStack.getTagCompound();
    }

    private void makeSupportBlocksShaped() {
        ItemStack shapeCard = inventoryHelper.getStackInSlot(BuilderContainer.SLOT_TAB);
        BlockPos dimension = ShapeCardItem.getClampedDimension(shapeCard, BuilderConfiguration.maxBuilderDimension);
        BlockPos offset = ShapeCardItem.getClampedOffset(shapeCard, BuilderConfiguration.maxBuilderOffset);
        Shape shape = ShapeCardItem.getShape(shapeCard);
        Map<BlockPos, IBlockState> blocks = new HashMap<>();
        ShapeCardItem.composeFormula(shapeCard, shape.getFormulaFactory().createFormula(), getWorld(), getPos(), dimension, offset, blocks, BuilderConfiguration.maxBuilderDimension * 256 * BuilderConfiguration.maxBuilderDimension, false, false, null);
        for (Map.Entry<BlockPos, IBlockState> entry : blocks.entrySet()) {
            BlockPos p = entry.getKey();
            if (getWorld().isAirBlock(p)) {
                getWorld().setBlockState(p, BuilderSetup.supportBlock.getDefaultState().withProperty(SupportBlock.STATUS, SupportBlock.STATUS_OK), 3);
            }
        }
    }

    private void makeSupportBlocks() {
        if (isShapeCard()) {
            makeSupportBlocksShaped();
            return;
        }

        SpaceChamberRepository.SpaceChamberChannel chamberChannel = calculateBox();
        if (chamberChannel != null) {
            int dimension = chamberChannel.getDimension();
            World world = DimensionManager.getWorld(dimension);
            if (world == null) {
                return;
            }

            BlockPos.MutableBlockPos src = new BlockPos.MutableBlockPos();
            BlockPos.MutableBlockPos dest = new BlockPos.MutableBlockPos();
            for (int x = minBox.getX(); x <= maxBox.getX(); x++) {
                for (int y = minBox.getY(); y <= maxBox.getY(); y++) {
                    for (int z = minBox.getZ(); z <= maxBox.getZ(); z++) {
                        src.setPos(x, y, z);
                        sourceToDest(src, dest);
                        IBlockState srcState = world.getBlockState(src);
                        Block srcBlock = srcState.getBlock();
                        IBlockState dstState = world.getBlockState(dest);
                        Block dstBlock = dstState.getBlock();
                        int error = SupportBlock.STATUS_OK;
                        if (mode != MODE_COPY) {
                            TileEntity srcTileEntity = world.getTileEntity(src);
                            TileEntity dstTileEntity = getWorld().getTileEntity(dest);

                            int error1 = isMovable(world, src, srcBlock, srcTileEntity);
                            int error2 = isMovable(getWorld(), dest, dstBlock, dstTileEntity);
                            error = Math.max(error1, error2);
                        }
                        if (isEmpty(srcState, srcBlock) && !isEmpty(dstState, dstBlock)) {
                            getWorld().setBlockState(src, BuilderSetup.supportBlock.getDefaultState().withProperty(SupportBlock.STATUS, error), 3);
                        }
                        if (isEmpty(dstState, dstBlock) && !isEmpty(srcState, srcBlock)) {
                            getWorld().setBlockState(dest, BuilderSetup.supportBlock.getDefaultState().withProperty(SupportBlock.STATUS, error), 3);
                        }
                    }
                }
            }
        }
    }

    private void clearSupportBlocksShaped() {
        ItemStack shapeCard = inventoryHelper.getStackInSlot(BuilderContainer.SLOT_TAB);
        BlockPos dimension = ShapeCardItem.getClampedDimension(shapeCard, BuilderConfiguration.maxBuilderDimension);
        BlockPos offset = ShapeCardItem.getClampedOffset(shapeCard, BuilderConfiguration.maxBuilderOffset);
        Shape shape = ShapeCardItem.getShape(shapeCard);
        Map<BlockPos, IBlockState> blocks = new HashMap<>();
        ShapeCardItem.composeFormula(shapeCard, shape.getFormulaFactory().createFormula(), getWorld(), getPos(), dimension, offset, blocks, BuilderConfiguration.maxSpaceChamberDimension * BuilderConfiguration.maxSpaceChamberDimension * BuilderConfiguration.maxSpaceChamberDimension, false, false, null);
        for (Map.Entry<BlockPos, IBlockState> entry : blocks.entrySet()) {
            BlockPos block = entry.getKey();
            if (getWorld().getBlockState(block).getBlock() == BuilderSetup.supportBlock) {
                getWorld().setBlockToAir(block);
            }
        }
    }

    public void clearSupportBlocks() {
        if (getWorld().isRemote) {
            // Don't do anything on the client.
            return;
        }

        if (isShapeCard()) {
            clearSupportBlocksShaped();
            return;
        }

        SpaceChamberRepository.SpaceChamberChannel chamberChannel = calculateBox();
        if (chamberChannel != null) {
            int dimension = chamberChannel.getDimension();
            World world = DimensionManager.getWorld(dimension);

            BlockPos.MutableBlockPos src = new BlockPos.MutableBlockPos();
            BlockPos.MutableBlockPos dest = new BlockPos.MutableBlockPos();
            for (int x = minBox.getX(); x <= maxBox.getX(); x++) {
                for (int y = minBox.getY(); y <= maxBox.getY(); y++) {
                    for (int z = minBox.getZ(); z <= maxBox.getZ(); z++) {
                        src.setPos(x, y, z);
                        if (world != null) {
                            Block srcBlock = world.getBlockState(src).getBlock();
                            if (srcBlock == BuilderSetup.supportBlock) {
                                world.setBlockToAir(src);
                            }
                        }
                        sourceToDest(src, dest);
                        Block dstBlock = getWorld().getBlockState(dest).getBlock();
                        if (dstBlock == BuilderSetup.supportBlock) {
                            getWorld().setBlockToAir(dest);
                        }
                    }
                }
            }
        }
    }

    public boolean hasLoopMode() {
        return loopMode;
    }

    public void setLoopMode(boolean loopMode) {
        this.loopMode = loopMode;
        markDirtyClient();
    }

    public boolean hasEntityMode() {
        return entityMode;
    }

    public void setEntityMode(boolean entityMode) {
        this.entityMode = entityMode;
        markDirtyClient();
    }

    public boolean hasSupportMode() {
        return supportMode;
    }

    public void setSupportMode(boolean supportMode) {
        this.supportMode = supportMode;
        if (supportMode) {
            makeSupportBlocks();
        } else {
            clearSupportBlocks();
        }
        markDirtyClient();
    }

    public boolean isSilent() {
        return silent;
    }

    public void setSilent(boolean silent) {
        this.silent = silent;
        markDirtyClient();
    }

    public int getMode() {
        return mode;
    }

    public void setMode(int mode) {
        if (mode != this.mode) {
            this.mode = mode;
            restartScan();
            markDirtyClient();
        }
    }

    public void resetBox() {
        boxValid = false;
    }

    public int getAnchor() {
        return anchor;
    }

    public void setAnchor(int anchor) {
        if (supportMode) {
            clearSupportBlocks();
        }
        boxValid = false;

        this.anchor = anchor;

        if (isShapeCard()) {
            // If there is a shape card we modify it for the new settings.
            ItemStack shapeCard = inventoryHelper.getStackInSlot(BuilderContainer.SLOT_TAB);
            BlockPos dimension = ShapeCardItem.getDimension(shapeCard);
            BlockPos minBox = positionBox(dimension);
            int dx = dimension.getX();
            int dy = dimension.getY();
            int dz = dimension.getZ();

            BlockPos offset = new BlockPos(minBox.getX() + (int) Math.ceil(dx / 2), minBox.getY() + (int) Math.ceil(dy / 2), minBox.getZ() + (int) Math.ceil(dz / 2));
            ShapeCardItem.setOffset(shapeCard, offset.getX(), offset.getY(), offset.getZ());
        }

        if (supportMode) {
            makeSupportBlocks();
        }
        markDirtyClient();
    }

    // Give a dimension, return a min coordinate of the box right in front of the builder
    private BlockPos positionBox(BlockPos dimension) {
        IBlockState state = getWorld().getBlockState(getPos());
        EnumFacing direction = state.getValue(BuilderSetup.builderBlock.FACING_HORIZ);
        int spanX = dimension.getX();
        int spanY = dimension.getY();
        int spanZ = dimension.getZ();
        int x = 0;
        int y;
        int z = 0;
        y = -((anchor == ANCHOR_NE || anchor == ANCHOR_NW) ? spanY - 1 : 0);
        switch (direction) {
            case SOUTH:
                x = -((anchor == ANCHOR_NE || anchor == ANCHOR_SE) ? spanX - 1 : 0);
                z = -spanZ;
                break;
            case NORTH:
                x = 1 - spanX + ((anchor == ANCHOR_NE || anchor == ANCHOR_SE) ? spanX - 1 : 0);
                z = 1;
                break;
            case WEST:
                x = 1;
                z = -((anchor == ANCHOR_NE || anchor == ANCHOR_SE) ? spanZ - 1 : 0);
                break;
            case EAST:
                x = -spanX;
                z = -((anchor == ANCHOR_NE || anchor == ANCHOR_SE) ? 0 : spanZ - 1);
                break;
            case DOWN:
            case UP:
            default:
                break;
        }
        return new BlockPos(x, y, z);
    }


    public int getRotate() {
        return rotate;
    }

    public void setRotate(int rotate) {
        if (supportMode) {
            clearSupportBlocks();
        }
        boxValid = false;
        this.rotate = rotate;
        if (supportMode) {
            makeSupportBlocks();
        }
        markDirtyClient();
    }

    @Override
    public void setPowerInput(int powered) {
        boolean o = isMachineEnabled();
        super.setPowerInput(powered);
        boolean n = isMachineEnabled();
        if (o != n) {
            if (loopMode || (n && scan == null)) {
                restartScan();
            }
        }
    }

    private void createProjection(SpaceChamberRepository.SpaceChamberChannel chamberChannel) {
        BlockPos minC = rotate(chamberChannel.getMinCorner());
        BlockPos maxC = rotate(chamberChannel.getMaxCorner());
        BlockPos minCorner = new BlockPos(Math.min(minC.getX(), maxC.getX()), Math.min(minC.getY(), maxC.getY()), Math.min(minC.getZ(), maxC.getZ()));
        BlockPos maxCorner = new BlockPos(Math.max(minC.getX(), maxC.getX()), Math.max(minC.getY(), maxC.getY()), Math.max(minC.getZ(), maxC.getZ()));

        IBlockState state = getWorld().getBlockState(getPos());
        EnumFacing direction = state.getValue(BuilderSetup.builderBlock.FACING_HORIZ);
        int xCoord = getPos().getX();
        int yCoord = getPos().getY();
        int zCoord = getPos().getZ();
        int spanX = maxCorner.getX() - minCorner.getX();
        int spanY = maxCorner.getY() - minCorner.getY();
        int spanZ = maxCorner.getZ() - minCorner.getZ();
        switch (direction) {
            case SOUTH:
                projDx = xCoord + EnumFacing.NORTH.getDirectionVec().getX() - minCorner.getX() - ((anchor == ANCHOR_NE || anchor == ANCHOR_SE) ? spanX : 0);
                projDz = zCoord + EnumFacing.NORTH.getDirectionVec().getZ() - minCorner.getZ() - spanZ;
                break;
            case NORTH:
                projDx = xCoord + EnumFacing.SOUTH.getDirectionVec().getX() - minCorner.getX() - spanX + ((anchor == ANCHOR_NE || anchor == ANCHOR_SE) ? spanX : 0);
                projDz = zCoord + EnumFacing.SOUTH.getDirectionVec().getZ() - minCorner.getZ();
                break;
            case WEST:
                projDx = xCoord + EnumFacing.EAST.getDirectionVec().getX() - minCorner.getX();
                projDz = zCoord + EnumFacing.EAST.getDirectionVec().getZ() - minCorner.getZ() - ((anchor == ANCHOR_NE || anchor == ANCHOR_SE) ? spanZ : 0);
                break;
            case EAST:
                projDx = xCoord + EnumFacing.WEST.getDirectionVec().getX() - minCorner.getX() - spanX;
                projDz = zCoord + EnumFacing.WEST.getDirectionVec().getZ() - minCorner.getZ() - spanZ + ((anchor == ANCHOR_NE || anchor == ANCHOR_SE) ? spanZ : 0);
                break;
            case DOWN:
            case UP:
            default:
                break;
        }
        projDy = yCoord - minCorner.getY() - ((anchor == ANCHOR_NE || anchor == ANCHOR_NW) ? spanY : 0);
    }

    private void calculateBox(NBTTagCompound cardCompound) {
        int channel = cardCompound.getInteger("channel");

        SpaceChamberRepository repository = SpaceChamberRepository.getChannels(getWorld());
        SpaceChamberRepository.SpaceChamberChannel chamberChannel = repository.getChannel(channel);
        BlockPos minCorner = chamberChannel.getMinCorner();
        BlockPos maxCorner = chamberChannel.getMaxCorner();
        if (minCorner == null || maxCorner == null) {
            return;
        }

        if (boxValid) {
            // Double check if the box is indeed still valid.
            if (minCorner.equals(minBox) && maxCorner.equals(maxBox)) {
                return;
            }
        }

        boxValid = true;
        cardType = ShapeCardItem.CARD_SPACE;

        createProjection(chamberChannel);

        minBox = minCorner;
        maxBox = maxCorner;
        restartScan();
    }

    private void checkStateServerShaped() {
        float factor = getInfusedFactor();
        for (int i = 0; i < BuilderConfiguration.quarryBaseSpeed + (factor * BuilderConfiguration.quarryInfusionSpeedFactor); i++) {
            if (scan != null) {
                handleBlockShaped();
            }
        }
    }


    @Override
    public InventoryHelper getInventoryHelper() {
        return inventoryHelper;
    }

    @Override
    public void update() {
        if (!getWorld().isRemote) {
            checkStateServer();
        }
    }

    private void checkStateServer() {
        if (!isMachineEnabled() && loopMode) {
            return;
        }

        if (scan == null) {
            return;
        }

        if (isShapeCard()) {
            if (!isMachineEnabled()) {
                chunkUnload();
                return;
            }
            checkStateServerShaped();
            return;
        }

        SpaceChamberRepository.SpaceChamberChannel chamberChannel = calculateBox();
        if (chamberChannel == null) {
            scan = null;
            markDirty();
            return;
        }

        int dimension = chamberChannel.getDimension();
        World world = DimensionManager.getWorld(dimension);
        if (world == null) {
            // The other location must be loaded.
            return;
        }

        if (mode == MODE_COLLECT) {
            collectItems(world);
        } else {
            float factor = getInfusedFactor();
            for (int i = 0; i < 2 + (factor * 40); i++) {
                if (scan != null) {
                    handleBlock(world);
                }
            }
        }
    }

    private void collectItems(World world) {
        // Collect item mode
        collectCounter--;
        if (collectCounter > 0) {
            return;
        }
        collectCounter = BuilderConfiguration.collectTimer;
        if (!loopMode) {
            scan = null;
        }

        int rf = getEnergyStored(EnumFacing.DOWN);
        float area = (maxBox.getX() - minBox.getX() + 1) * (maxBox.getY() - minBox.getY() + 1) * (maxBox.getZ() - minBox.getZ() + 1);
        float infusedFactor = (4.0f - getInfusedFactor()) / 4.0f;
        int rfNeeded = (int) (BuilderConfiguration.collectRFPerTickPerArea * area * infusedFactor) * BuilderConfiguration.collectTimer;
        if (rfNeeded > rf) {
            // Not enough energy.
            return;
        }
        consumeEnergy(rfNeeded);

        AxisAlignedBB bb = new AxisAlignedBB(minBox.getX() - .8, minBox.getY() - .8, minBox.getZ() - .8, maxBox.getX() + .8, maxBox.getY() + .8, maxBox.getZ() + .8);
        List<Entity> items = world.getEntitiesWithinAABB(Entity.class, bb);
        for (Entity entity : items) {
            if (entity instanceof EntityItem) {
                if (collectItem(world, infusedFactor, (EntityItem) entity)) {
                    return;
                }
            } else if (entity instanceof EntityXPOrb) {
                if (collectXP(world, infusedFactor, (EntityXPOrb) entity)) {
                    return;
                }
            }
        }
    }

    private boolean collectXP(World world, float infusedFactor, EntityXPOrb orb) {
        int rf;
        int rfNeeded;

        int xp = orb.getXpValue();

        rf = getEnergyStored(EnumFacing.DOWN);
        rfNeeded = (int) (BuilderConfiguration.collectRFPerXP * infusedFactor * xp);
        if (rfNeeded > rf) {
            // Not enough energy.
            return true;
        }

        collectXP += xp;

        int bottles = collectXP / 7;
        if (bottles > 0) {
            if (ItemStackTools.isEmpty(insertItem(new ItemStack(Items.EXPERIENCE_BOTTLE, bottles)))) {
                collectXP = collectXP % 7;
                world.removeEntity(orb);
                consumeEnergy(rfNeeded);
            } else {
                collectXP = 0;
            }
        }

        return false;
    }

    private boolean collectItem(World world, float infusedFactor, EntityItem item) {
        int rf;
        int rfNeeded;

        ItemStack stack = item.getEntityItem();

        rf = getEnergyStored(EnumFacing.DOWN);
        rfNeeded = (int) (BuilderConfiguration.collectRFPerItem * infusedFactor) * ItemStackTools.getStackSize(stack);
        if (rfNeeded > rf) {
            // Not enough energy.
            return true;
        }
        consumeEnergy(rfNeeded);

        world.removeEntity(item);
        stack = insertItem(stack);
        if (ItemStackTools.isValid(stack)) {
            BlockPos position = item.getPosition();
            EntityItem entityItem = new EntityItem(getWorld(), position.getX(), position.getY(), position.getZ(), stack);
            mcjty.lib.tools.WorldTools.spawnEntity(getWorld(), entityItem);
        }
        return false;
    }

    private void calculateBoxShaped() {
        ItemStack shapeCard = inventoryHelper.getStackInSlot(BuilderContainer.SLOT_TAB);
        if (ItemStackTools.isEmpty(shapeCard)) {
            return;
        }
        BlockPos dimension = ShapeCardItem.getClampedDimension(shapeCard, BuilderConfiguration.maxBuilderDimension);
        BlockPos offset = ShapeCardItem.getClampedOffset(shapeCard, BuilderConfiguration.maxBuilderOffset);

        BlockPos minCorner = ShapeCardItem.getMinCorner(getPos(), dimension, offset);
        BlockPos maxCorner = ShapeCardItem.getMaxCorner(getPos(), dimension, offset);
        if (minCorner.getY() < 0) {
            minCorner = new BlockPos(minCorner.getX(), 0, minCorner.getZ());
        } else if (minCorner.getY() > 255) {
            minCorner = new BlockPos(minCorner.getX(), 255, minCorner.getZ());
        }
        if (maxCorner.getY() < 0) {
            maxCorner = new BlockPos(maxCorner.getX(), 0, maxCorner.getZ());
        } else if (maxCorner.getY() > 255) {
            maxCorner = new BlockPos(maxCorner.getX(), 255, maxCorner.getZ());
        }

        if (boxValid) {
            // Double check if the box is indeed still valid.
            if (minCorner.equals(minBox) && maxCorner.equals(maxBox)) {
                return;
            }
        }

        boxValid = true;
        cardType = shapeCard.getItemDamage();

        cachedBlocks = null;
        cachedChunk = null;
        cachedVoidableBlocks = null;
        minBox = minCorner;
        maxBox = maxCorner;
        restartScan();
    }

    private SpaceChamberRepository.SpaceChamberChannel calculateBox() {
        NBTTagCompound tc = hasCard();
        if (tc == null) {
            return null;
        }

        int channel = tc.getInteger("channel");
        if (channel == -1) {
            return null;
        }

        SpaceChamberRepository repository = SpaceChamberRepository.getChannels(getWorld());
        SpaceChamberRepository.SpaceChamberChannel chamberChannel = repository.getChannel(channel);
        if (chamberChannel == null) {
            return null;
        }

        calculateBox(tc);

        if (!boxValid) {
            return null;
        }
        return chamberChannel;
    }

    private Map<BlockPos, IBlockState> getCachedBlocks(ChunkPos chunk) {
        if ((chunk != null && !chunk.equals(cachedChunk)) || (chunk == null && cachedChunk != null)) {
            cachedBlocks = null;
        }

        if (cachedBlocks == null) {
            cachedBlocks = new HashMap<>();
            ItemStack shapeCard = inventoryHelper.getStackInSlot(BuilderContainer.SLOT_TAB);
            Shape shape = ShapeCardItem.getShape(shapeCard);
            boolean solid = ShapeCardItem.isSolid(shapeCard);
            BlockPos dimension = ShapeCardItem.getClampedDimension(shapeCard, BuilderConfiguration.maxBuilderDimension);
            BlockPos offset = ShapeCardItem.getClampedOffset(shapeCard, BuilderConfiguration.maxBuilderOffset);
            boolean forquarry = !ShapeCardItem.isNormalShapeCard(shapeCard);
            ShapeCardItem.composeFormula(shapeCard, shape.getFormulaFactory().createFormula(), getWorld(), getPos(), dimension, offset, cachedBlocks, BuilderConfiguration.maxSpaceChamberDimension * BuilderConfiguration.maxSpaceChamberDimension * BuilderConfiguration.maxSpaceChamberDimension, solid, forquarry, chunk);
            cachedChunk = chunk;
        }
        return cachedBlocks;
    }

    private void handleBlockShaped() {
        for (int i = 0; i < 100; i++) {
            if (scan == null) {
                return;
            }
            Map<BlockPos, IBlockState> blocks = getCachedBlocks(new ChunkPos(scan.getX() >> 4, scan.getZ() >> 4));
            if (blocks.containsKey(scan)) {
                IBlockState state = blocks.get(scan);
                if (!handleSingleBlock(state)) {
                    nextLocation();
                }
                return;
            } else {
                nextLocation();
            }
        }
    }

    private int getCardType() {
        if (cardType == ShapeCardItem.CARD_UNKNOWN) {
            ItemStack card = inventoryHelper.getStackInSlot(BuilderContainer.SLOT_TAB);
            if (ItemStackTools.isValid(card)) {
                cardType = card.getItemDamage();
            }
        }
        return cardType;
    }

    // Return true if we have to wait at this spot.
    private boolean handleSingleBlock(IBlockState pickState) {
        BlockPos srcPos = scan;
        int sx = scan.getX();
        int sy = scan.getY();
        int sz = scan.getZ();
        if (!chunkLoad(sx, sz)) {
            // The chunk is not available and we could not chunkload it. We have to wait.
            return true;
        }

        int rfNeeded;

        switch (getCardType()) {
            case ShapeCardItem.CARD_PUMP_LIQUID:
            case ShapeCardItem.CARD_PUMP:
            case ShapeCardItem.CARD_PUMP_CLEAR:
                rfNeeded = BuilderConfiguration.builderRfPerLiquid;
                break;
            case ShapeCardItem.CARD_VOID:
                rfNeeded = (int) (BuilderConfiguration.builderRfPerQuarry * BuilderConfiguration.voidShapeCardFactor);
                break;
            case ShapeCardItem.CARD_QUARRY_FORTUNE:
            case ShapeCardItem.CARD_QUARRY_CLEAR_FORTUNE:
                rfNeeded = (int) (BuilderConfiguration.builderRfPerQuarry * BuilderConfiguration.fortunequarryShapeCardFactor);
                break;
            case ShapeCardItem.CARD_QUARRY_SILK:
            case ShapeCardItem.CARD_QUARRY_CLEAR_SILK:
                rfNeeded = (int) (BuilderConfiguration.builderRfPerQuarry * BuilderConfiguration.silkquarryShapeCardFactor);
                break;
            case ShapeCardItem.CARD_QUARRY:
            case ShapeCardItem.CARD_QUARRY_CLEAR:
                rfNeeded = BuilderConfiguration.builderRfPerQuarry;
                break;
            case ShapeCardItem.CARD_SHAPE:
                rfNeeded = BuilderConfiguration.builderRfPerOperation;
                break;
            default:
                rfNeeded = 0;
                break;
        }

        Block block = null;
        if (getCardType() != ShapeCardItem.CARD_SHAPE && getCardType() != ShapeCardItem.CARD_PUMP_LIQUID) {
            IBlockState state = getWorld().getBlockState(srcPos);
            block = state.getBlock();
            if (!isEmpty(state, block)) {
                float hardness;
                if (isFluidBlock(block)) {
                    hardness = 1.0f;
                } else {
                    if (getCachedVoidableBlocks().contains(block)) {
                        rfNeeded = (int) (BuilderConfiguration.builderRfPerQuarry * BuilderConfiguration.voidShapeCardFactor);
                    }
                    hardness = block.getBlockHardness(state, getWorld(), srcPos);
                }
                rfNeeded *= (int) ((hardness + 1) * 2);
            }
        }

        rfNeeded = (int) (rfNeeded * (3.0f - getInfusedFactor()) / 3.0f);

        if (rfNeeded > getEnergyStored(EnumFacing.DOWN)) {
            // Not enough energy.
            return true;
        }

        switch (getCardType()) {
            case ShapeCardItem.CARD_PUMP_LIQUID:
                return placeLiquidBlock(rfNeeded, srcPos);
            case ShapeCardItem.CARD_PUMP:
            case ShapeCardItem.CARD_PUMP_CLEAR:
                return pumpBlock(rfNeeded, srcPos, block);
            case ShapeCardItem.CARD_VOID:
                return voidBlock(rfNeeded, srcPos, block);
            case ShapeCardItem.CARD_QUARRY:
            case ShapeCardItem.CARD_QUARRY_CLEAR:
                return quarryBlock(rfNeeded, srcPos, block);
            case ShapeCardItem.CARD_QUARRY_FORTUNE:
            case ShapeCardItem.CARD_QUARRY_CLEAR_FORTUNE:
                return quarryBlock(rfNeeded, srcPos, block);
            case ShapeCardItem.CARD_QUARRY_SILK:
            case ShapeCardItem.CARD_QUARRY_CLEAR_SILK:
                return silkQuarryBlock(rfNeeded, srcPos, block);
            case ShapeCardItem.CARD_SHAPE:
                return buildBlock(rfNeeded, srcPos, pickState);
        }
        return true;
    }

    private boolean buildBlock(int rfNeeded, BlockPos srcPos, IBlockState pickState) {
        if (isEmptyOrReplacable(getWorld(), srcPos)) {
            ItemStack stack = consumeBlock(getWorld(), srcPos, pickState);
            if (ItemStackTools.isEmpty(stack)) {
                return true;    // We could not find a block. Wait
            }

            FakePlayer fakePlayer = getHarvester();
            Integer origMeta = pickState != null ? pickState.getBlock().getMetaFromState(pickState) : null;
            IBlockState newState = placeBlockAt(getWorld(), srcPos, stack, origMeta, fakePlayer);

            if (!silent) {
                SoundTools.playSound(getWorld(), newState.getBlock().getSoundType().getBreakSound(), srcPos.getX(), srcPos.getY(), srcPos.getZ(), 1.0f, 1.0f);
            }

            consumeEnergy(rfNeeded);
        }
        return false;
    }

    private IBlockState placeBlockAt(World world, BlockPos pos, ItemStack stack, @Nullable Integer origMeta, FakePlayer fakePlayer) {
        Item item = stack.getItem();
        if (item instanceof ItemBlock) {
            ItemBlock itemBlock = (ItemBlock) item;
            if (origMeta == null) {
                origMeta = itemBlock.getDamage(stack);
            }
            IBlockState newState = itemBlock.block.getStateFromMeta(origMeta);
            itemBlock.placeBlockAt(stack, fakePlayer, world, pos, EnumFacing.UP, 0, 0, 0, newState);
            return newState;
        } else {
            InventoryTools.onItemUseWithStack(item, stack, fakePlayer, world, pos.down(), EnumHand.MAIN_HAND, EnumFacing.UP, 0, 0, 0);
            return world.getBlockState(pos);
        }
    }

    private Set<Block> getCachedVoidableBlocks() {
        if (cachedVoidableBlocks == null) {
            ItemStack card = inventoryHelper.getStackInSlot(BuilderContainer.SLOT_TAB);
            if (ItemStackTools.isValid(card) && card.getItem() == BuilderSetup.shapeCardItem) {
                cachedVoidableBlocks = ShapeCardItem.getVoidedBlocks(card);
            } else {
                cachedVoidableBlocks = Collections.emptySet();
            }
        }
        return cachedVoidableBlocks;
    }

    private void clearOrDirtBlock(int rfNeeded, int sx, int sy, int sz, Block block, boolean clear) {
        BlockPos spos = new BlockPos(sx, sy, sz);
        if (clear) {
            getWorld().setBlockToAir(spos);
        } else {
            getWorld().setBlockState(spos, getReplacementBlock().getDefaultState(), 2);       // No block update!
        }
        consumeEnergy(rfNeeded);
        if (!silent) {
            SoundTools.playSound(getWorld(), block.getSoundType().getBreakSound(), sx, sy, sz, 1.0f, 1.0f);
        }
    }

    private Block getReplacementBlock() {
        return BuilderConfiguration.getQuarryReplace();
    }

    private boolean silkQuarryBlock(int rfNeeded, BlockPos srcPos, Block block) {
        IBlockState srcState = getWorld().getBlockState(srcPos);
        int xCoord = getPos().getX();
        int yCoord = getPos().getY();
        int zCoord = getPos().getZ();
        int sx = srcPos.getX();
        int sy = srcPos.getY();
        int sz = srcPos.getZ();
        if (sx >= xCoord - 1 && sx <= xCoord + 1 && sy >= yCoord - 1 && sy <= yCoord + 1 && sz >= zCoord - 1 && sz <= zCoord + 1) {
            // Skip a 3x3x3 block around the builder.
            return false;
        }
        if (isEmpty(srcState, block)) {
            return false;
        }
        if (block.getBlockHardness(srcState, getWorld(), srcPos) >= 0) {
            boolean clear = ShapeCardItem.isClearingQuarry(getCardType());
            if ((!clear) && block == getReplacementBlock()) {
                // We can skip dirt if we are not clearing.
                return false;
            }
            if ((!BuilderConfiguration.quarryTileEntities) && getWorld().getTileEntity(srcPos) != null) {
                // Skip tile entities
                return false;
            }

            FakePlayer fakePlayer = getHarvester();
            if (allowedToBreak(srcState, getWorld(), srcPos, fakePlayer)) {
                ItemStack filter = getStackInSlot(BuilderContainer.SLOT_FILTER);
                if (ItemStackTools.isValid(filter)) {
                    getFilterCache();
                    if (filterCache != null) {
                        boolean match = filterCache.match(block.getItem(getWorld(), srcPos, srcState));
                        if (!match) {
                            consumeEnergy(Math.min(rfNeeded, BuilderConfiguration.builderRfPerSkipped));
                            return false;   // Skip this
                        }
                    }
                }
                if (getCachedVoidableBlocks().contains(block)) {
                    clearOrDirtBlock(rfNeeded, sx, sy, sz, block, clear);
                } else {
                    List<ItemStack> drops;
                    if (block.canSilkHarvest(getWorld(), srcPos, srcState, fakePlayer)) {
                        ItemStack drop;
                        try {
                            drop = (ItemStack) CommonProxy.Block_getSilkTouch.invoke(block, srcState);
                        } catch (IllegalAccessException e) {
                            throw new RuntimeException(e);
                        } catch (InvocationTargetException e) {
                            throw new RuntimeException(e);
                        }
                        drops = new ArrayList<>();
                        if (ItemStackTools.isValid(drop)) {
                            drops.add(drop);
                        }
                        net.minecraftforge.event.ForgeEventFactory.fireBlockHarvesting(drops, getWorld(), pos, srcState, 0, 1.0f, true, fakePlayer);
                    } else {
                        drops = block.getDrops(getWorld(), srcPos, srcState, 0);
                        net.minecraftforge.event.ForgeEventFactory.fireBlockHarvesting(drops, getWorld(), pos, srcState, 0, 1.0f, false, fakePlayer);
                    }
                    if (checkAndInsertItems(block, drops)) {
                        clearOrDirtBlock(rfNeeded, sx, sy, sz, block, clear);
                    } else {
                        return true;    // Not enough room. Wait
                    }
                }
            }
        }
        return false;
    }

    private void getFilterCache() {
        if (filterCache == null) {
            filterCache = StorageFilterItem.getCache(inventoryHelper.getStackInSlot(BuilderContainer.SLOT_FILTER));
        }
    }

    private static boolean allowedToBreak(IBlockState state, World world, BlockPos pos, EntityPlayer entityPlayer) {
        if (!state.getBlock().canEntityDestroy(state, world, pos, entityPlayer)) {
            return false;
        }
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(world, pos, state, entityPlayer);
        MinecraftForge.EVENT_BUS.post(event);
        return !event.isCanceled();
    }

    private boolean quarryBlock(int rfNeeded, BlockPos srcPos, Block block) {
        IBlockState srcState = getWorld().getBlockState(srcPos);
        int xCoord = getPos().getX();
        int yCoord = getPos().getY();
        int zCoord = getPos().getZ();
        int sx = srcPos.getX();
        int sy = srcPos.getY();
        int sz = srcPos.getZ();
        if (sx >= xCoord - 1 && sx <= xCoord + 1 && sy >= yCoord - 1 && sy <= yCoord + 1 && sz >= zCoord - 1 && sz <= zCoord + 1) {
            // Skip a 3x3x3 block around the builder.
            return false;
        }
        if (isEmpty(srcState, block)) {
            return false;
        }
        if (block.getBlockHardness(srcState, getWorld(), srcPos) >= 0) {
            boolean clear = ShapeCardItem.isClearingQuarry(getCardType());
            if ((!clear) && block == getReplacementBlock()) {
                // We can skip dirt if we are not clearing.
                return false;
            }
            if ((!BuilderConfiguration.quarryTileEntities) && getWorld().getTileEntity(srcPos) != null) {
                // Skip tile entities
                return false;
            }

            FakePlayer fakePlayer = getHarvester();
            if (allowedToBreak(srcState, getWorld(), srcPos, fakePlayer)) {
                ItemStack filter = getStackInSlot(BuilderContainer.SLOT_FILTER);
                if (ItemStackTools.isValid(filter)) {
                    getFilterCache();
                    if (filterCache != null) {
                        boolean match = filterCache.match(block.getItem(getWorld(), srcPos, srcState));
                        if (!match) {
                            consumeEnergy(Math.min(rfNeeded, BuilderConfiguration.builderRfPerSkipped));
                            return false;   // Skip this
                        }
                    }
                }
                if (getCachedVoidableBlocks().contains(block)) {
                    clearOrDirtBlock(rfNeeded, sx, sy, sz, block, clear);
                } else {
                    int fortune = (getCardType() == ShapeCardItem.CARD_QUARRY_FORTUNE || getCardType() == ShapeCardItem.CARD_QUARRY_CLEAR_FORTUNE) ? 3 : 0;
                    List<ItemStack> drops = block.getDrops(getWorld(), srcPos, srcState, fortune);
                    net.minecraftforge.event.ForgeEventFactory.fireBlockHarvesting(drops, getWorld(), pos, srcState, fortune, 1.0f, false, fakePlayer);
                    if (checkAndInsertItems(block, drops)) {
                        clearOrDirtBlock(rfNeeded, sx, sy, sz, block, clear);
                    } else {
                        return true;    // Not enough room. Wait
                    }
                }
            }
        }
        return false;
    }

    private static boolean isFluidBlock(Block block) {
        return block instanceof BlockLiquid || block instanceof BlockFluidBase;
    }

    private static int getFluidLevel(IBlockState srcState) {
        if (srcState.getBlock() instanceof BlockLiquid) {
            return srcState.getValue(BlockLiquid.LEVEL);
        }
        if (srcState.getBlock() instanceof BlockFluidBase) {
            return srcState.getValue(BlockFluidBase.LEVEL);
        }
        return -1;
    }

    private boolean placeLiquidBlock(int rfNeeded, BlockPos srcPos) {

        if (isEmptyOrReplacable(getWorld(), srcPos)) {
            FluidStack stack = consumeLiquid(getWorld(), srcPos);
            if (stack == null) {
                return true;    // We could not find a block. Wait
            }

            // We assume here the liquid is placable.
            Block block = stack.getFluid().getBlock();
            FakePlayer fakePlayer = getHarvester();
            getWorld().setBlockState(srcPos, block.getDefaultState(), 11);

            if (!silent) {
                SoundTools.playSound(getWorld(), block.getSoundType().getBreakSound(), srcPos.getX(), srcPos.getY(), srcPos.getZ(), 1.0f, 1.0f);
            }

            consumeEnergy(rfNeeded);
        }
        return false;
    }

    private boolean pumpBlock(int rfNeeded, BlockPos srcPos, Block block) {
        Fluid fluid = FluidRegistry.lookupFluidForBlock(block);
        if (fluid == null) {
            return false;
        }
        if (!isFluidBlock(block)) {
            return false;
        }

        IBlockState srcState = getWorld().getBlockState(srcPos);
        if (getFluidLevel(srcState) != 0) {
            return false;
        }


        if (block.getBlockHardness(srcState, getWorld(), srcPos) >= 0) {
            FakePlayer fakePlayer = getHarvester();
            if (allowedToBreak(srcState, getWorld(), srcPos, fakePlayer)) {
                if (checkAndInsertFluids(fluid)) {
                    consumeEnergy(rfNeeded);
                    boolean clear = getCardType() == ShapeCardItem.CARD_PUMP_CLEAR;
                    if (clear) {
                        getWorld().setBlockToAir(srcPos);
                    } else {
                        getWorld().setBlockState(srcPos, getReplacementBlock().getDefaultState(), 2);       // No block update!
                    }
                    if (!silent) {
                        SoundTools.playSound(getWorld(), block.getSoundType().getBreakSound(), srcPos.getX(), srcPos.getY(), srcPos.getZ(), 1.0f, 1.0f);
                    }
                    return false;
                }
                return true;    // No room in tanks or not a valid tank: wait
            }
        }
        return false;
    }

    private boolean voidBlock(int rfNeeded, BlockPos srcPos, Block block) {
        IBlockState srcState = getWorld().getBlockState(srcPos);
        int xCoord = getPos().getX();
        int yCoord = getPos().getY();
        int zCoord = getPos().getZ();
        int sx = srcPos.getX();
        int sy = srcPos.getY();
        int sz = srcPos.getZ();
        if (sx >= xCoord - 1 && sx <= xCoord + 1 && sy >= yCoord - 1 && sy <= yCoord + 1 && sz >= zCoord - 1 && sz <= zCoord + 1) {
            // Skip a 3x3x3 block around the builder.
            return false;
        }
        FakePlayer fakePlayer = getHarvester();
        if (allowedToBreak(srcState, getWorld(), srcPos, fakePlayer)) {
            if (block.getBlockHardness(srcState, getWorld(), srcPos) >= 0) {
                ItemStack filter = getStackInSlot(BuilderContainer.SLOT_FILTER);
                if (ItemStackTools.isValid(filter)) {
                    getFilterCache();
                    if (filterCache != null) {
                        boolean match = filterCache.match(block.getItem(getWorld(), srcPos, srcState));
                        if (!match) {
                            consumeEnergy(Math.min(rfNeeded, BuilderConfiguration.builderRfPerSkipped));
                            return false;   // Skip this
                        }
                    }
                }

                if (!silent) {
                    SoundTools.playSound(getWorld(), block.getSoundType().getBreakSound(), sx, sy, sz, 1.0f, 1.0f);
                }
                getWorld().setBlockToAir(srcPos);
                consumeEnergy(rfNeeded);
            }
        }
        return false;
    }

    private void handleBlock(World world) {
        BlockPos srcPos = scan;
        BlockPos destPos = sourceToDest(scan);
        int x = scan.getX();
        int y = scan.getY();
        int z = scan.getZ();
        int destX = destPos.getX();
        int destY = destPos.getY();
        int destZ = destPos.getZ();

        switch (mode) {
            case MODE_COPY:
                copyBlock(world, srcPos, getWorld(), destPos);
                break;
            case MODE_MOVE:
                if (entityMode) {
                    moveEntities(world, x, y, z, getWorld(), destX, destY, destZ);
                }
                moveBlock(world, srcPos, getWorld(), destPos, rotate);
                break;
            case MODE_BACK:
                if (entityMode) {
                    moveEntities(getWorld(), destX, destY, destZ, world, x, y, z);
                }
                moveBlock(getWorld(), destPos, world, srcPos, oppositeRotate());
                break;
            case MODE_SWAP:
                if (entityMode) {
                    swapEntities(world, x, y, z, getWorld(), destX, destY, destZ);
                }
                swapBlock(world, srcPos, getWorld(), destPos);
                break;
        }

        nextLocation();
    }

    private static Random random = new Random();

    // Also works if block is null and just picks the first available block.
    private ItemStack findAndConsumeBlock(IItemHandler inventory, World srcWorld, BlockPos srcPos, IBlockState state) {
        if (state == null) {
            // We are not looking for a specific block. Pick a random one out of the chest.
            List<Integer> slots = new ArrayList<>();
            for (int i = 0; i < inventory.getSlots(); i++) {
                ItemStack stack = inventory.getStackInSlot(i);
                if (isPlacable(stack)) {
                    slots.add(i);
                }
            }
            if (slots.isEmpty()) {
                return ItemStackTools.getEmptyStack();
            }
            int randomSlot = slots.get(random.nextInt(slots.size()));
            return inventory.extractItem(randomSlot, 1, false);
        } else {
            Block block = state.getBlock();
            ItemStack srcItem = block.getItem(srcWorld, srcPos, state);
            if (isPlacable(srcItem)) {
                for (int i = 0; i < inventory.getSlots(); i++) {
                    ItemStack stack = inventory.getStackInSlot(i);
                    if (ItemStackTools.isValid(stack) && stack.isItemEqual(srcItem)) {
                        return inventory.extractItem(i, 1, false);
                    }
                }
            }
        }
        return ItemStackTools.getEmptyStack();
    }

    private boolean isPlacable(ItemStack stack) {
        if (ItemStackTools.isEmpty(stack)) {
            return false;
        }
        Item item = stack.getItem();
        return item instanceof ItemBlock || item instanceof ItemSkull || item instanceof ItemBlockSpecial
                || item instanceof IPlantable;
    }

    // Also works if block is null and just picks the first available block.
    private ItemStack findAndConsumeBlock(IInventory inventory, World srcWorld, BlockPos srcPos, IBlockState state) {
        if (state == null) {
            // We are not looking for a specific block. Pick a random one out of the chest.
            List<Integer> slots = new ArrayList<>();
            for (int i = 0; i < inventory.getSizeInventory(); i++) {
                ItemStack stack = inventory.getStackInSlot(i);
                if (isPlacable(stack)) {
                    slots.add(i);
                }
            }
            if (slots.isEmpty()) {
                return ItemStackTools.getEmptyStack();
            }
            int randomSlot = slots.get(random.nextInt(slots.size()));
            return inventory.decrStackSize(randomSlot, 1);
        } else {
            Block block = state.getBlock();
            ItemStack srcItem = block.getItem(srcWorld, srcPos, state);
            int meta = block.getMetaFromState(state);
            for (int i = 0; i < inventory.getSizeInventory(); i++) {
                ItemStack stack = inventory.getStackInSlot(i);
                if (ItemStackTools.isValid(stack) && stack.isItemEqual(srcItem)) {
                    return inventory.decrStackSize(i, 1);
                }
            }
        }
        return ItemStackTools.getEmptyStack();
    }

    // To protect against mods doing bad things we have to check
    // the items that we try to insert.
    private boolean checkValidItems(Block block, List<ItemStack> items) {
        for (ItemStack stack : items) {
            if (ItemStackTools.isValid(stack) && stack.getItem() == null) {
                Logging.logError("Builder tried to quarry " + block.getRegistryName().toString() + " and it returned null item!");
                Broadcaster.broadcast(getWorld(), pos.getX(), pos.getY(), pos.getZ(), "Builder tried to quarry "
                                + block.getRegistryName().toString() + " and it returned null item!\nPlease report to mod author!",
                        10);
                return false;
            }
        }
        return true;
    }

    private boolean checkAndInsertFluids(Fluid fluid) {
        if (checkFluidTank(fluid, getPos().up(), EnumFacing.DOWN)) {
            return true;
        }
        if (checkFluidTank(fluid, getPos().down(), EnumFacing.UP)) {
            return true;
        }
        return false;
    }

    private boolean checkFluidTank(Fluid fluid, BlockPos up, EnumFacing side) {
        TileEntity te = getWorld().getTileEntity(up);
        if (te != null && te.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null)) {
            IFluidHandler handler = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null);
            FluidStack fluidStack = new FluidStack(fluid, 1000);
            int amount = handler.fill(fluidStack, false);
            if (amount == 1000) {
                handler.fill(fluidStack, true);
                return true;
            }
        }
        return false;
    }

    private boolean checkAndInsertItems(Block block, List<ItemStack> items) {
        TileEntity te = getWorld().getTileEntity(getPos().up());
        if (!checkValidItems(block, items)) {
            return true;    // We don't wait for this. Just skip the item
        }
        boolean ok = InventoryHelper.insertItemsAtomic(items, te, EnumFacing.DOWN);
        if (!ok) {
            te = getWorld().getTileEntity(getPos().down());
            ok = InventoryHelper.insertItemsAtomic(items, te, EnumFacing.UP);
        }
        return ok;
    }

    // Return what could not be inserted
    private ItemStack insertItem(ItemStack s) {
        s = InventoryHelper.insertItem(getWorld(), getPos(), EnumFacing.UP, s);
        if (ItemStackTools.isValid(s)) {
            s = InventoryHelper.insertItem(getWorld(), getPos(), EnumFacing.DOWN, s);
        }
        return s;
    }

    /**
     * Consume a block out of an inventory. Returns a blockstate
     * from that inventory or else null if nothing could be found.
     * If the given blockstate parameter is null then a random block will be
     * returned. Otherwise the returned block has to match.
     */
    private ItemStack consumeBlock(EnumFacing direction, World srcWorld, BlockPos srcPos, IBlockState state) {
        TileEntity te = getWorld().getTileEntity(getPos().offset(direction));
        if (te != null) {
            if (te.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, direction.getOpposite())) {
                IItemHandler capability = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, direction.getOpposite());
                return findAndConsumeBlock(capability, srcWorld, srcPos, state);
            } else if (te instanceof IInventory) {
                return findAndConsumeBlock((IInventory) te, srcWorld, srcPos, state);
            }
        }
        return ItemStackTools.getEmptyStack();
    }

    private FluidStack consumeLiquid(World srcWorld, BlockPos srcPos) {
        FluidStack b = consumeLiquid(EnumFacing.UP, srcWorld, srcPos);
        if (b == null) {
            b = consumeLiquid(EnumFacing.DOWN, srcWorld, srcPos);
        }
        return b;
    }

    private FluidStack consumeLiquid(EnumFacing direction, World srcWorld, BlockPos srcPos) {
        TileEntity te = getWorld().getTileEntity(getPos().offset(direction));
        if (te != null) {
            if (te.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, direction.getOpposite())) {
                IFluidHandler capability = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, direction.getOpposite());
                return findAndConsumeLiquid(capability, srcWorld, srcPos);
            }
            if (te.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null)) {
                IFluidHandler capability = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null);
                return findAndConsumeLiquid(capability, srcWorld, srcPos);
            }
        }
        return null;
    }

    private FluidStack findAndConsumeLiquid(IFluidHandler tank, World srcWorld, BlockPos srcPos) {
        for (IFluidTankProperties properties : tank.getTankProperties()) {
            FluidStack contents = properties.getContents();
            if (contents != null) {
                if (contents.getFluid() != null) {
                    if (contents.amount >= 1000) {
                        FluidStack drained = tank.drain(new FluidStack(contents.getFluid(), 1000, contents.tag), true);
                        System.out.println("drained = " + drained);
                        return drained;
                    }
                }
            }
        }
        return null;
    }


    private ItemStack consumeBlock(World srcWorld, BlockPos srcPos, IBlockState state) {
        ItemStack b = consumeBlock(EnumFacing.UP, srcWorld, srcPos, state);
        if (ItemStackTools.isEmpty(b)) {
            b = consumeBlock(EnumFacing.DOWN, srcWorld, srcPos, state);
        }
        return b;
    }

    public static BuilderSetup.BlockInformation getBlockInformation(World world, BlockPos pos, Block block, TileEntity tileEntity) {
        IBlockState state = world.getBlockState(pos);
        if (isEmpty(state, block)) {
            return BuilderSetup.BlockInformation.FREE;
        }

        FakePlayer fakePlayer = getHarvester();
        if (!allowedToBreak(state, world, pos, fakePlayer)) {
            return BuilderSetup.BlockInformation.INVALID;
        }

        BuilderSetup.BlockInformation blockInformation = BuilderSetup.getBlockInformation(block);
        if (tileEntity != null) {
            switch (BuilderConfiguration.teMode) {
                case MOVE_FORBIDDEN:
                    return BuilderSetup.BlockInformation.INVALID;
                case MOVE_WHITELIST:
                    if (blockInformation == null || blockInformation.getBlockLevel() == SupportBlock.STATUS_ERROR) {
                        return BuilderSetup.BlockInformation.INVALID;
                    }
                    break;
                case MOVE_BLACKLIST:
                    if (blockInformation != null && blockInformation.getBlockLevel() == SupportBlock.STATUS_ERROR) {
                        return BuilderSetup.BlockInformation.INVALID;
                    }
                    break;
                case MOVE_ALLOWED:
                    break;
            }
        }
        if (blockInformation != null) {
            return blockInformation;
        }
        return BuilderSetup.BlockInformation.OK;
    }

    private int isMovable(World world, BlockPos pos, Block block, TileEntity tileEntity) {
        return getBlockInformation(world, pos, block, tileEntity).getBlockLevel();
    }

    public static boolean isEmptyOrReplacable(World world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        if (block.isReplaceable(world, pos)) {
            return true;
        }
        return isEmpty(state, block);
    }

    // True if this block can just be overwritten (i.e. are or support block)
    public static boolean isEmpty(IBlockState state, Block block) {
        if (block == null) {
            return true;
        }
        if (block.getMaterial(state) == Material.AIR) {
            return true;
        }
        if (block == BuilderSetup.supportBlock) {
            return true;
        }
        return false;
    }

    private void clearBlock(World world, BlockPos pos) {
        if (supportMode) {
            world.setBlockState(pos, BuilderSetup.supportBlock.getDefaultState(), 3);
        } else {
            world.setBlockToAir(pos);
        }
    }

    private int oppositeRotate() {
        switch (rotate) {
            case 1:
                return 3;
            case 3:
                return 1;
        }
        return rotate;
    }

    private int rotateMeta(Block block, int meta, BuilderSetup.BlockInformation information, int rotMode) {
        Item item = Item.getItemFromBlock(block);
        if (item != null && item.getHasSubtypes()) {
            // If the item has subtypes we cannot rotate it.
            return meta;
        }

        switch (information.getRotateInfo()) {
            // @todo do this the proper way!
            case BuilderSetup.BlockInformation.ROTATE_mfff:
//                switch (rotMode) {
//                    case 0: return meta;
//                    case 1: {
//                        EnumFacing dir = ForgeDirection.values()[meta & 7];
//                        return (meta & 8) | dir.getRotation(ForgeDirection.UP).ordinal();
//                    }
//                    case 2: {
//                        ForgeDirection dir = ForgeDirection.values()[meta & 7];
//                        return (meta & 8) | dir.getOpposite().ordinal();
//                    }
//                    case 3: {
//                        ForgeDirection dir = ForgeDirection.values()[meta & 7];
//                        return (meta & 8) | dir.getOpposite().getRotation(ForgeDirection.UP).ordinal();
//                    }
//                }
                break;
            case BuilderSetup.BlockInformation.ROTATE_mmmm:
                return meta;
        }
        return meta;
    }

    private void copyBlock(World world, BlockPos srcPos, World destWorld, BlockPos destPos) {
        int rf = getEnergyStored(EnumFacing.DOWN);
        int rfNeeded = (int) (BuilderConfiguration.builderRfPerOperation * getDimensionCostFactor(world, destWorld) * (4.0f - getInfusedFactor()) / 4.0f);
        if (rfNeeded > rf) {
            // Not enough energy.
            return;
        }

        if (isEmptyOrReplacable(destWorld, destPos)) {
            if (world.isAirBlock(srcPos)) {
                return;
            }
            IBlockState state = world.getBlockState(srcPos);
            ItemStack consumedStack = consumeBlock(world, srcPos, state);
            if (ItemStackTools.isEmpty(consumedStack)) {
                return;
            }

            Block origBlock = state.getBlock();
            int origMeta = origBlock.getMetaFromState(state);
            BuilderSetup.BlockInformation information = getBlockInformation(world, srcPos, origBlock, null);
            origMeta = rotateMeta(origBlock, origMeta, information, rotate);

            FakePlayer fakePlayer = getHarvester();
            IBlockState newState = placeBlockAt(destWorld, destPos, consumedStack, origMeta, fakePlayer);
            destWorld.setBlockState(destPos, newState, 3);  // placeBlockAt can reset the orientation. Restore it here

            if (!silent) {
                SoundTools.playSound(destWorld, origBlock.getSoundType().getBreakSound(), destPos.getX(), destPos.getY(), destPos.getZ(), 1.0f, 1.0f);
            }

            consumeEnergy(rfNeeded);
        }
    }

    private double getDimensionCostFactor(World world, World destWorld) {
        return destWorld.provider.getDimension() == world.provider.getDimension() ? 1.0 : BuilderConfiguration.dimensionCostFactor;
    }

    private boolean consumeEntityEnergy(int rfNeeded, int rfNeededPlayer, Entity entity) {
        int rf = getEnergyStored(EnumFacing.DOWN);
        int rfn;
        if (entity instanceof EntityPlayer) {
            rfn = rfNeededPlayer;
        } else {
            rfn = rfNeeded;
        }
        if (rfn > rf) {
            // Not enough energy.
            return true;
        } else {
            consumeEnergy(rfn);
        }
        return false;
    }

    private void moveEntities(World world, int x, int y, int z, World destWorld, int destX, int destY, int destZ) {
        int rfNeeded = (int) (BuilderConfiguration.builderRfPerEntity * getDimensionCostFactor(world, destWorld) * (4.0f - getInfusedFactor()) / 4.0f);
        int rfNeededPlayer = (int) (BuilderConfiguration.builderRfPerPlayer * getDimensionCostFactor(world, destWorld) * (4.0f - getInfusedFactor()) / 4.0f);

        // Check for entities.
        List entities = world.getEntitiesWithinAABBExcludingEntity(null, new AxisAlignedBB(x - .1, y - .1, z - .1, x + 1.1, y + 1.1, z + 1.1));
        for (Object o : entities) {
            Entity entity = (Entity) o;

            if (consumeEntityEnergy(rfNeeded, rfNeededPlayer, entity)) {
                return;
            }

            double newX = destX + (entity.posX - x);
            double newY = destY + (entity.posY - y);
            double newZ = destZ + (entity.posZ - z);

            teleportEntity(world, destWorld, entity, newX, newY, newZ);
        }
    }

    private void swapEntities(World world, int x, int y, int z, World destWorld, int destX, int destY, int destZ) {
        int rfNeeded = (int) (BuilderConfiguration.builderRfPerEntity * getDimensionCostFactor(world, destWorld) * (4.0f - getInfusedFactor()) / 4.0f);
        int rfNeededPlayer = (int) (BuilderConfiguration.builderRfPerPlayer * getDimensionCostFactor(world, destWorld) * (4.0f - getInfusedFactor()) / 4.0f);

        // Check for entities.
        List entitiesSrc = world.getEntitiesWithinAABBExcludingEntity(null, new AxisAlignedBB(x, y, z, x + 1, y + 1, z + 1));
        List entitiesDst = destWorld.getEntitiesWithinAABBExcludingEntity(null, new AxisAlignedBB(destX, destY, destZ, destX + 1, destY + 1, destZ + 1));
        for (Object o : entitiesSrc) {
            Entity entity = (Entity) o;
            if (isEntityInBlock(x, y, z, entity)) {
                if (consumeEntityEnergy(rfNeeded, rfNeededPlayer, entity)) {
                    return;
                }

                double newX = destX + (entity.posX - x);
                double newY = destY + (entity.posY - y);
                double newZ = destZ + (entity.posZ - z);
                teleportEntity(world, destWorld, entity, newX, newY, newZ);
            }
        }
        for (Object o : entitiesDst) {
            Entity entity = (Entity) o;
            if (isEntityInBlock(destX, destY, destZ, entity)) {
                if (consumeEntityEnergy(rfNeeded, rfNeededPlayer, entity)) {
                    return;
                }

                double newX = x + (entity.posX - destX);
                double newY = y + (entity.posY - destY);
                double newZ = z + (entity.posZ - destZ);
                teleportEntity(destWorld, world, entity, newX, newY, newZ);
            }
        }
    }

    private void teleportEntity(World world, World destWorld, Entity entity, double newX, double newY, double newZ) {
        if (!TeleportationTools.allowTeleport(entity, world.provider.getDimension(), entity.getPosition(), destWorld.provider.getDimension(), new BlockPos(newX, newY, newZ))) {
            return;
        }
        if (entity instanceof EntityPlayer) {
            if (world.provider.getDimension() != destWorld.provider.getDimension()) {
                TeleportationTools.teleportToDimension((EntityPlayer) entity, destWorld.provider.getDimension(), newX, newY, newZ);
            }
            entity.setPositionAndUpdate(newX, newY, newZ);
        } else {
            if (world.provider.getDimension() != destWorld.provider.getDimension()) {
                NBTTagCompound tagCompound = new NBTTagCompound();
                float rotationYaw = entity.rotationYaw;
                float rotationPitch = entity.rotationPitch;
                entity.writeToNBT(tagCompound);
                Class<? extends Entity> entityClass = entity.getClass();
                world.removeEntity(entity);

                try {
                    Entity newEntity = entityClass.getConstructor(World.class).newInstance(destWorld);
                    newEntity.readFromNBT(tagCompound);
                    newEntity.setLocationAndAngles(newX, newY, newZ, rotationYaw, rotationPitch);
                    mcjty.lib.tools.WorldTools.spawnEntity(destWorld, newEntity);
                } catch (Exception e) {
                }
            } else {
                entity.setLocationAndAngles(newX, newY, newZ, entity.rotationYaw, entity.rotationPitch);
                destWorld.updateEntityWithOptionalForce(entity, false);
            }
        }
    }


    private boolean isEntityInBlock(int x, int y, int z, Entity entity) {
        if (entity.posX >= x && entity.posX < x + 1 && entity.posY >= y && entity.posY < y + 1 && entity.posZ >= z && entity.posZ < z + 1) {
            return true;
        }
        return false;
    }

    private void moveBlock(World world, BlockPos srcPos, World destWorld, BlockPos destPos, int rotMode) {
        IBlockState destState = destWorld.getBlockState(destPos);
        Block destBlock = destState.getBlock();
        if (isEmpty(destState, destBlock)) {
            IBlockState state = world.getBlockState(srcPos);
            Block origBlock = state.getBlock();
            if (isEmpty(state, origBlock)) {
                return;
            }
            TileEntity origTileEntity = world.getTileEntity(srcPos);
            BuilderSetup.BlockInformation information = getBlockInformation(world, srcPos, origBlock, origTileEntity);
            if (information.getBlockLevel() == SupportBlock.STATUS_ERROR) {
                return;
            }

            int rf = getEnergyStored(EnumFacing.DOWN);
            int rfNeeded = (int) (BuilderConfiguration.builderRfPerOperation * getDimensionCostFactor(world, destWorld) * information.getCostFactor() * (4.0f - getInfusedFactor()) / 4.0f);
            if (rfNeeded > rf) {
                // Not enough energy.
                return;
            } else {
                consumeEnergy(rfNeeded);
            }

            int origMeta = origBlock.getMetaFromState(state);
            origMeta = rotateMeta(origBlock, origMeta, information, rotMode);

            NBTTagCompound tc = null;
            if (origTileEntity != null) {
                tc = new NBTTagCompound();
                origTileEntity.writeToNBT(tc);
                world.removeTileEntity(srcPos);
            }
            clearBlock(world, srcPos);

            IBlockState newDestState = origBlock.getStateFromMeta(origMeta);
            destWorld.setBlockState(destPos, newDestState, 3);
            if (origTileEntity != null && tc != null) {
                setTileEntityNBT(destWorld, tc, destPos, newDestState);
            }
            if (!silent) {
                SoundTools.playSound(world, origBlock.getSoundType().getBreakSound(), srcPos.getX(), srcPos.getY(), srcPos.getZ(), 1.0f, 1.0f);
                SoundTools.playSound(destWorld, origBlock.getSoundType().getBreakSound(), destPos.getX(), destPos.getY(), destPos.getZ(), 1.0f, 1.0f);
            }
        }
    }

    private void setTileEntityNBT(World destWorld, NBTTagCompound tc, BlockPos destpos, IBlockState newDestState) {
        tc.setInteger("x", destpos.getX());
        tc.setInteger("y", destpos.getY());
        tc.setInteger("z", destpos.getZ());
        TileEntity tileEntity = TileEntity.create(destWorld, tc);
        if (tileEntity != null) {
            destWorld.getChunkFromBlockCoords(destpos).addTileEntity(tileEntity);
            tileEntity.markDirty();
            destWorld.notifyBlockUpdate(destpos, newDestState, newDestState, 3);
        }
    }

    private void swapBlock(World world, BlockPos srcPos, World destWorld, BlockPos dstPos) {
        IBlockState srcState = world.getBlockState(srcPos);
        Block srcBlock = srcState.getBlock();
        TileEntity srcTileEntity = world.getTileEntity(srcPos);

        IBlockState dstState = destWorld.getBlockState(dstPos);
        Block dstBlock = dstState.getBlock();
        TileEntity dstTileEntity = destWorld.getTileEntity(dstPos);

        if (isEmpty(srcState, srcBlock) && isEmpty(dstState, dstBlock)) {
            return;
        }

        BuilderSetup.BlockInformation srcInformation = getBlockInformation(world, srcPos, srcBlock, srcTileEntity);
        if (srcInformation.getBlockLevel() == SupportBlock.STATUS_ERROR) {
            return;
        }

        BuilderSetup.BlockInformation dstInformation = getBlockInformation(destWorld, dstPos, dstBlock, dstTileEntity);
        if (dstInformation.getBlockLevel() == SupportBlock.STATUS_ERROR) {
            return;
        }

        int rf = getEnergyStored(EnumFacing.DOWN);
        int rfNeeded = (int) (BuilderConfiguration.builderRfPerOperation * getDimensionCostFactor(world, destWorld) * srcInformation.getCostFactor() * (4.0f - getInfusedFactor()) / 4.0f);
        rfNeeded += (int) (BuilderConfiguration.builderRfPerOperation * getDimensionCostFactor(world, destWorld) * dstInformation.getCostFactor() * (4.0f - getInfusedFactor()) / 4.0f);
        if (rfNeeded > rf) {
            // Not enough energy.
            return;
        } else {
            consumeEnergy(rfNeeded);
        }

        int srcMeta = srcBlock.getMetaFromState(srcState);
        srcMeta = rotateMeta(srcBlock, srcMeta, srcInformation, oppositeRotate());
        int dstMeta = dstBlock.getMetaFromState(dstState);
        dstMeta = rotateMeta(dstBlock, dstMeta, dstInformation, rotate);

        world.removeTileEntity(srcPos);
        world.setBlockToAir(srcPos);
        destWorld.removeTileEntity(dstPos);
        destWorld.setBlockToAir(dstPos);

        IBlockState newDstState = srcBlock.getStateFromMeta(srcMeta);
        destWorld.setBlockState(dstPos, newDstState, 3);
//        destWorld.setBlockMetadataWithNotify(destX, destY, destZ, srcMeta, 3);
        if (srcTileEntity != null) {
            srcTileEntity.validate();
            destWorld.setTileEntity(dstPos, srcTileEntity);
            srcTileEntity.markDirty();
            destWorld.notifyBlockUpdate(dstPos, newDstState, newDstState, 3);
        }

        IBlockState newSrcState = dstBlock.getStateFromMeta(dstMeta);
        world.setBlockState(srcPos, newSrcState, 3);
//        world.setBlockMetadataWithNotify(x, y, z, dstMeta, 3);
        if (dstTileEntity != null) {
            dstTileEntity.validate();
            world.setTileEntity(srcPos, dstTileEntity);
            dstTileEntity.markDirty();
            world.notifyBlockUpdate(srcPos, newSrcState, newSrcState, 3);
        }

        if (!silent) {
            if (!isEmpty(srcState, srcBlock)) {
                SoundTools.playSound(world, srcBlock.getSoundType().getBreakSound(), srcPos.getX(), srcPos.getY(), srcPos.getZ(), 1.0f, 1.0f);
            }
            if (!isEmpty(dstState, dstBlock)) {
                SoundTools.playSound(destWorld, dstBlock.getSoundType().getBreakSound(), dstPos.getX(), dstPos.getY(), dstPos.getZ(), 1.0f, 1.0f);
            }
        }
    }

    private BlockPos sourceToDest(BlockPos source) {
        return rotate(source).add(projDx, projDy, projDz);
    }

    private BlockPos rotate(BlockPos c) {
        switch (rotate) {
            case 0:
                return c;
            case 1:
                return new BlockPos(-c.getZ(), c.getY(), c.getX());
            case 2:
                return new BlockPos(-c.getX(), c.getY(), -c.getZ());
            case 3:
                return new BlockPos(c.getZ(), c.getY(), -c.getX());
        }
        return c;
    }

    private void sourceToDest(BlockPos source, BlockPos.MutableBlockPos dest) {
        rotate(source, dest);
        dest.setPos(dest.getX() + projDx, dest.getY() + projDy, dest.getZ() + projDz);
    }


    private void rotate(BlockPos c, BlockPos.MutableBlockPos dest) {
        switch (rotate) {
            case 0:
                dest.setPos(c);
                break;
            case 1:
                dest.setPos(-c.getZ(), c.getY(), c.getX());
                break;
            case 2:
                dest.setPos(-c.getX(), c.getY(), -c.getZ());
                break;
            case 3:
                dest.setPos(c.getZ(), c.getY(), -c.getX());
                break;
        }
    }

    private void restartScan() {
        chunkUnload();
        if (loopMode || (isMachineEnabled() && scan == null)) {
            if (getCardType() == ShapeCardItem.CARD_SPACE) {
                calculateBox();
                scan = minBox;
            } else if (getCardType() != ShapeCardItem.CARD_UNKNOWN) {
                calculateBoxShaped();
                // We start at the top for a quarry or shape building
                scan = new BlockPos(minBox.getX(), maxBox.getY(), minBox.getZ());
            }
            cachedBlocks = null;
            cachedChunk = null;
            cachedVoidableBlocks = null;
        } else {
            scan = null;
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        chunkUnload();
    }

    private void chunkUnload() {
        if (forcedChunk != null && ticket != null) {
            ForgeChunkManager.unforceChunk(ticket, forcedChunk);
            forcedChunk = null;
        }
    }

    private boolean chunkLoad(int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;

        if (RFToolsTools.chunkLoaded(getWorld(), new BlockPos(x, 0, z))) {
            return true;
        }

        if (BuilderConfiguration.quarryChunkloads) {
            if (ticket == null) {
                ticket = ForgeChunkManager.requestTicket(RFTools.instance, getWorld(), ForgeChunkManager.Type.NORMAL);
                if (ticket == null) {
                    // Chunk is not loaded and we can't get a ticket.
                    return false;
                }
            }

            ChunkPos pair = new ChunkPos(cx, cz);
            if (pair.equals(forcedChunk)) {
                return true;
            }
            if (forcedChunk != null) {
                ForgeChunkManager.unforceChunk(ticket, forcedChunk);
            }
            forcedChunk = pair;
            ForgeChunkManager.forceChunk(ticket, forcedChunk);
            return true;
        }
        // Chunk is not loaded and we don't do chunk loading so we cannot proceed.
        return false;
    }


    private void nextLocation() {
        if (scan != null) {
            int x = scan.getX();
            int y = scan.getY();
            int z = scan.getZ();

            if (getCardType() == ShapeCardItem.CARD_SPACE) {
                nextLocationNormal(x, y, z);
            } else {
                nextLocationQuarry(x, y, z);
            }
        }
    }

    private void nextLocationQuarry(int x, int y, int z) {
        if (x >= maxBox.getX() || ((x + 1) % 16 == 0)) {
            if (z >= maxBox.getZ() || ((z + 1) % 16 == 0)) {
                if (y <= minBox.getY()) {
                    if (x < maxBox.getX()) {
                        x++;
                        z = (z >> 4) << 4;
                        y = maxBox.getY();
                        scan = new BlockPos(x, y, z);
                    } else if (z < maxBox.getZ()) {
                        x = minBox.getX();
                        z++;
                        y = maxBox.getY();
                        scan = new BlockPos(x, y, z);
                    } else {
                        restartScan();
                    }
                } else {
                    scan = new BlockPos((x >> 4) << 4, y - 1, (z >> 4) << 4);
                }
            } else {
                scan = new BlockPos((x >> 4) << 4, y, z + 1);
            }
        } else {
            scan = new BlockPos(x + 1, y, z);
        }
    }

    private void nextLocationNormal(int x, int y, int z) {
        if (x >= maxBox.getX()) {
            if (z >= maxBox.getZ()) {
                if (y >= maxBox.getY()) {
                    if (mode != MODE_SWAP || isShapeCard()) {
                        restartScan();
                    } else {
                        // We don't restart in swap mode.
                        scan = null;
                    }
                } else {
                    scan = new BlockPos(minBox.getX(), y + 1, minBox.getZ());
                }
            } else {
                scan = new BlockPos(minBox.getX(), y, z + 1);
            }
        } else {
            scan = new BlockPos(x + 1, y, z);
        }
    }

    @Override
    public int[] getSlotsForFace(EnumFacing side) {
        return BuilderContainer.factory.getAccessibleSlots();
    }

    @Override
    public boolean canInsertItem(int index, ItemStack itemStackIn, EnumFacing direction) {
        return BuilderContainer.factory.isInputSlot(index);
    }

    @Override
    public boolean canExtractItem(int index, ItemStack stack, EnumFacing direction) {
        return BuilderContainer.factory.isOutputSlot(index);
    }

    @Override
    public ItemStack decrStackSize(int index, int amount) {
        if (index == BuilderContainer.SLOT_TAB && ItemStackTools.isValid(inventoryHelper.getStackInSlot(index)) && amount > 0) {
            // Restart if we go from having a stack to not having stack or the other way around.
            refreshSettings();
        }
        if (index == BuilderContainer.SLOT_FILTER) {
            filterCache = null;
        }
        return inventoryHelper.decrStackSize(index, amount);
    }

    @Override
    public void setInventorySlotContents(int index, ItemStack stack) {
        if (index == BuilderContainer.SLOT_TAB && ((ItemStackTools.isEmpty(stack)
                && ItemStackTools.isValid(inventoryHelper.getStackInSlot(index)))
                || (ItemStackTools.isValid(stack) && ItemStackTools.isEmpty(inventoryHelper.getStackInSlot(index))))) {
            // Restart if we go from having a stack to not having stack or the other way around.
            refreshSettings();
        }
        if (index == BuilderContainer.SLOT_FILTER) {
            filterCache = null;
        }
        inventoryHelper.setInventorySlotContents(getInventoryStackLimit(), index, stack);
    }

    private void refreshSettings() {
        clearSupportBlocks();
        cachedBlocks = null;
        cachedChunk = null;
        cachedVoidableBlocks = null;
        boxValid = false;
        scan = null;
        cardType = ShapeCardItem.CARD_UNKNOWN;
    }

    @Override
    public int getInventoryStackLimit() {
        return 1;
    }

    @Override
    public boolean isUsable(EntityPlayer player) {
        return canPlayerAccess(player);
    }

    @Override
    public boolean isItemValidForSlot(int index, ItemStack stack) {
        return stack.getItem() == BuilderSetup.spaceChamberCardItem || stack.getItem() == BuilderSetup.shapeCardItem;
    }

    @Override
    public void readRestorableFromNBT(NBTTagCompound tagCompound) {
        super.readRestorableFromNBT(tagCompound);

        // Workaround to get the redstone mode for old builders to default to 'on'
        if (!tagCompound.hasKey("rsMode")) {
            rsMode = RedstoneMode.REDSTONE_ONREQUIRED;
        }


        readBufferFromNBT(tagCompound, inventoryHelper);
        mode = tagCompound.getInteger("mode");
        anchor = tagCompound.getInteger("anchor");
        rotate = tagCompound.getInteger("rotate");
        silent = tagCompound.getBoolean("silent");
        supportMode = tagCompound.getBoolean("support");
        entityMode = tagCompound.getBoolean("entityMode");
        loopMode = tagCompound.getBoolean("loopMode");
        scan = BlockPosTools.readFromNBT(tagCompound, "scan");
        minBox = BlockPosTools.readFromNBT(tagCompound, "minBox");
        maxBox = BlockPosTools.readFromNBT(tagCompound, "maxBox");
    }

    @Override
    public void writeRestorableToNBT(NBTTagCompound tagCompound) {
        super.writeRestorableToNBT(tagCompound);
        writeBufferToNBT(tagCompound, inventoryHelper);
        tagCompound.setInteger("mode", mode);
        tagCompound.setInteger("anchor", anchor);
        tagCompound.setInteger("rotate", rotate);
        tagCompound.setBoolean("silent", silent);
        tagCompound.setBoolean("support", supportMode);
        tagCompound.setBoolean("entityMode", entityMode);
        tagCompound.setBoolean("loopMode", loopMode);
        BlockPosTools.writeToNBT(tagCompound, "scan", scan);
        BlockPosTools.writeToNBT(tagCompound, "minBox", minBox);
        BlockPosTools.writeToNBT(tagCompound, "maxBox", maxBox);
    }

    // Request the current scan level.
    public void requestCurrentLevel() {
        RFToolsMessages.INSTANCE.sendToServer(new PacketRequestIntegerFromServer(RFTools.MODID, getPos(),
                CMD_GETLEVEL,
                CLIENTCMD_GETLEVEL));
    }

    public static int getCurrentLevelClientSide() {
        return currentLevel;
    }

    public int getCurrentLevel() {
        return scan == null ? -1 : scan.getY();
    }


    @Override
    public boolean execute(EntityPlayerMP playerMP, String command, Map<String, Argument> args) {
        boolean rc = super.execute(playerMP, command, args);
        if (rc) {
            return true;
        }
        if (CMD_MODE.equals(command)) {
            String m = args.get("rs").getString();
            setRSMode(RedstoneMode.getMode(m));
            return true;
        } else if (CMD_RESTART.equals(command)) {
            restartScan();
            return true;
        } else  if (CMD_SETMODE.equals(command)) {
            setMode(args.get("mode").getInteger());
            return true;
        } else if (CMD_SETANCHOR.equals(command)) {
            setAnchor(args.get("anchor").getInteger());
            return true;
        } else if (CMD_SETROTATE.equals(command)) {
            setRotate(args.get("rotate").getInteger());
            return true;
        } else if (CMD_SETSILENT.equals(command)) {
            setSilent(args.get("silent").getBoolean());
            return true;
        } else if (CMD_SETSUPPORT.equals(command)) {
            setSupportMode(args.get("support").getBoolean());
            return true;
        } else if (CMD_SETENTITIES.equals(command)) {
            setEntityMode(args.get("entities").getBoolean());
            return true;
        } else if (CMD_SETLOOP.equals(command)) {
            setLoopMode(args.get("loop").getBoolean());
            return true;
        }
        return false;
    }

    @Nonnull
    @Override
    public <T> List<T> executeWithResultList(String command, Map<String, Argument> args, Type<T> type) {
        List<T> rc = super.executeWithResultList(command, args, type);
        if (!rc.isEmpty()) {
            return rc;
        }
        if (PacketGetHudLog.CMD_GETHUDLOG.equals(command)) {
            return type.convert(getHudLog());
        }
        return rc;
    }

    @Override
    public <T> boolean execute(String command, List<T> list, Type<T> type) {
        boolean rc = super.execute(command, list, type);
        if (rc) {
            return true;
        }
        if (PacketGetHudLog.CLIENTCMD_GETHUDLOG.equals(command)) {
            clientHudLog = Type.STRING.convert(list);
            return true;
        }
        return false;
    }


    @Override
    public Integer executeWithResultInteger(String command, Map<String, Argument> args) {
        Integer rc = super.executeWithResultInteger(command, args);
        if (rc != null) {
            return rc;
        }
        if (CMD_GETLEVEL.equals(command)) {
            return scan == null ? -1 : scan.getY();
        }
        return null;
    }

    @Override
    public boolean execute(String command, Integer result) {
        boolean rc = super.execute(command, result);
        if (rc) {
            return true;
        }
        if (CLIENTCMD_GETLEVEL.equals(command)) {
            currentLevel = result;
            return true;
        }
        return false;
    }

    @SuppressWarnings("NullableProblems")
    @SideOnly(Side.CLIENT)
    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return new AxisAlignedBB(pos, pos.add(1, 2, 1));
    }
}
