package com.griefprevention.visualization.impl;

import com.griefprevention.util.IntVector;
import com.griefprevention.visualization.BlockBoundaryVisualization;
import com.griefprevention.visualization.Boundary;
import com.griefprevention.visualization.BoundaryVisualization;
import com.griefprevention.visualization.VisualizationType;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.PlayerData;
import me.ryanhamshire.GriefPrevention.util.BoundingBox;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import com.griefprevention.visualization.impl.SnapOverrideHelper.SnapOverride;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * A {@link BoundaryVisualization} implementation that displays clientside blocks along
 * {@link com.griefprevention.visualization.Boundary Boundaries}.
 */
public class FakeBlockVisualization extends BlockBoundaryVisualization
{

    protected boolean waterTransparent;

    /**
     * Construct a new {@code FakeBlockVisualization}.
     *
     * @param world the {@link World} being visualized in
     * @param visualizeFrom the {@link IntVector} representing the world coordinate being visualized from
     * @param height the height of the visualization
     */
    public FakeBlockVisualization(@NotNull World world, @NotNull IntVector visualizeFrom, int height) {
        super(world, visualizeFrom, height);

        // Water is considered transparent based on whether the visualization is initiated in water.
        waterTransparent = visualizeFrom.toBlock(world).getType() == Material.WATER;
    }

    @Override
    protected void apply(@NotNull Player player, @NotNull PlayerData playerData) {
        boolean previous = this.waterTransparent;
        try {
            this.waterTransparent = isPlayerSubmerged(player);
            super.apply(player, playerData);
        } finally {
            this.waterTransparent = previous;
        }
    }
    private boolean isPlayerSubmerged(@NotNull Player player) {
        Block feet = player.getLocation().getBlock();
        return isLiquidLike(feet);
    }

    private boolean isLiquidLike(@NotNull Block block)
    {
        return SnapOverrideHelper.isLiquidLike(block, waterTransparent);
    }

    @Override
    protected @NotNull Consumer<@NotNull IntVector> addCornerElements(@NotNull Boundary boundary)
    {
        VisualizationType type = boundary.type();

        return switch (type)
        {
            case SUBDIVISION_3D -> createElementAdder(Material.IRON_BLOCK.createBlockData(), type, true);
            case SUBDIVISION -> createElementAdder(Material.IRON_BLOCK.createBlockData(), type, false);
            case ADMIN_CLAIM -> createElementAdder(Material.GLOWSTONE.createBlockData(), type, false);
            case INITIALIZE_ZONE -> createElementAdder(Material.DIAMOND_BLOCK.createBlockData(), type, false);
            case CONFLICT_ZONE -> {
                BlockData fakeData = Material.REDSTONE_ORE.createBlockData();
                ((Lightable) fakeData).setLit(true);
                yield createElementAdder(fakeData, type, false);
            }
            default -> createElementAdder(Material.GLOWSTONE.createBlockData(), type, false);
        };
    }


    @Override
    protected @NotNull Consumer<@NotNull IntVector> addSideElements(@NotNull Boundary boundary)
    {
        // Determine BlockData from boundary type to cache for reuse in function.
        VisualizationType type = boundary.type();

        return switch (type)
        {
            case ADMIN_CLAIM -> createElementAdder(Material.PUMPKIN.createBlockData(), type, false);
            case SUBDIVISION -> createElementAdder(Material.WHITE_WOOL.createBlockData(), type, false);
            case SUBDIVISION_3D -> createElementAdder(Material.WHITE_WOOL.createBlockData(), type, true);
            case INITIALIZE_ZONE -> createElementAdder(Material.DIAMOND_BLOCK.createBlockData(), type, false);
            case CONFLICT_ZONE -> createElementAdder(Material.NETHERRACK.createBlockData(), type, false);
            default -> createElementAdder(Material.GOLD_BLOCK.createBlockData(), type, false);
        };
    }

    /**
     * Create an element-adder consumer, allowing subclasses to inject alternative behaviour.
     */
    protected @NotNull Consumer<@NotNull IntVector> createElementAdder(
            @NotNull BlockData fakeData,
            @NotNull VisualizationType type,
            boolean exactPlacement)
    {
        return exactPlacement ? addExactBlockElement(fakeData, type) : addBlockElement(fakeData, type);
    }

    @Override
    protected void draw(@NotNull Player player, @NotNull Boundary boundary)
    {
        // Use 3D-specific drawing for 3D subdivisions, otherwise use standard 2D drawing
        if (boundary.type() == VisualizationType.SUBDIVISION_3D) {
            drawRespectingYBoundaries(player, boundary);
        } else {
            // Always use the original visualization position, not the player's current position
            // This prevents side markers from moving with the player
            super.draw(player, boundary);
        }
    }

    /**
     * Draw a 3D subdivision boundary while respecting Y boundaries and limiting visualization
     * to only one block above and below the subclaim's Y limits.
     */
    private void drawRespectingYBoundaries(@NotNull Player player, @NotNull Boundary boundary)
    {
        BoundingBox area = boundary.bounds();
        Claim claim = boundary.claim();
        
        if (claim == null || !claim.is3D()) 
        {
            // Fallback to default behavior if claim is null or not 3D
            super.draw(player, boundary);
            return;
        }

        // Get the Y boundaries of the 3D subclaim
        int claimMinY = area.getMinY();
        int claimMaxY = area.getMaxY();

        // Replicate display zone logic (default values: displayZoneRadius=75)
        final int displayZoneRadius = 75;
        // Use the original visualizeFrom position, not player's current position to prevent movement
        int baseX = this.visualizeFrom.x();
        int baseZ = this.visualizeFrom.z();
        // For 3D subdivisions, ensure we include the entire claim's vertical span so both top and bottom are shown.
        int worldMinY = world.getMinHeight();
        int worldMaxY = world.getMaxHeight();
        int minShowY = Math.max(worldMinY, claimMinY - 1);
        int maxShowY = Math.min(worldMaxY, claimMaxY + 1);
        BoundingBox displayZoneArea = new BoundingBox(
                new IntVector(baseX - displayZoneRadius, minShowY, baseZ - displayZoneRadius),
                new IntVector(baseX + displayZoneRadius, maxShowY, baseZ + displayZoneRadius));
        
        // Trim to area - allows for simplified display containment check later.
        BoundingBox displayZone = displayZoneArea.intersection(area);

        // If area is not inside display zone, there is nothing to display.
        if (displayZone == null) return;

        Consumer<@NotNull IntVector> addCorner = addCornerElements(boundary);
        Consumer<@NotNull IntVector> addSide = addSideElements(boundary);

        // We only render at the top and bottom Y boundaries for 3D subdivisions.
        int[] yLevels = new int[] { claimMinY, claimMaxY };
        for (int y : yLevels)
        {
            if (y < world.getMinHeight() || y > world.getMaxHeight()) continue;

            // Short directional side markers next to corners only (no full ring)
            if (area.getLength() > 2)
            {
                addDisplayed3D(displayZone, new IntVector(area.getMinX() + 1, y, area.getMaxZ()), addSide);
                addDisplayed3D(displayZone, new IntVector(area.getMinX() + 1, y, area.getMinZ()), addSide);
                addDisplayed3D(displayZone, new IntVector(area.getMaxX() - 1, y, area.getMaxZ()), addSide);
                addDisplayed3D(displayZone, new IntVector(area.getMaxX() - 1, y, area.getMinZ()), addSide);
            }
            if (area.getWidth() > 2)
            {
                addDisplayed3D(displayZone, new IntVector(area.getMinX(), y, area.getMinZ() + 1), addSide);
                addDisplayed3D(displayZone, new IntVector(area.getMaxX(), y, area.getMinZ() + 1), addSide);
                addDisplayed3D(displayZone, new IntVector(area.getMinX(), y, area.getMaxZ() - 1), addSide);
                addDisplayed3D(displayZone, new IntVector(area.getMaxX(), y, area.getMaxZ() - 1), addSide);
            }

            // Corners at this Y level
            addDisplayed3D(displayZone, new IntVector(area.getMinX(), y, area.getMaxZ()), addCorner);
            addDisplayed3D(displayZone, new IntVector(area.getMaxX(), y, area.getMaxZ()), addCorner);
            addDisplayed3D(displayZone, new IntVector(area.getMinX(), y, area.getMinZ()), addCorner);
            addDisplayed3D(displayZone, new IntVector(area.getMaxX(), y, area.getMinZ()), addCorner);

            // Vertical indicator: exactly one white wool block above bottom corners and below top corners
            int verticalY;
            if (y == claimMinY) {
                verticalY = y + 1; // one block above bottom ring
            } else if (y == claimMaxY) {
                verticalY = y - 1; // one block below top ring
            } else {
                continue; // shouldn't happen, but guards future changes
            }
            if (verticalY >= world.getMinHeight() && verticalY <= world.getMaxHeight()) {
                // reuse exact-placement white wool consumer for 3D sides
                addDisplayed3D(displayZone, new IntVector(area.getMinX(), verticalY, area.getMaxZ()), addSide);
                addDisplayed3D(displayZone, new IntVector(area.getMaxX(), verticalY, area.getMaxZ()), addSide);
                addDisplayed3D(displayZone, new IntVector(area.getMinX(), verticalY, area.getMinZ()), addSide);
                addDisplayed3D(displayZone, new IntVector(area.getMaxX(), verticalY, area.getMinZ()), addSide);
            }
        }
    }

    /**
     * Add a display element if accessible (3D version that doesn't call parent's addDisplayed).
     */
    private void addDisplayed3D(
            @NotNull BoundingBox displayZone,
            @NotNull IntVector coordinate,
            @NotNull Consumer<@NotNull IntVector> addElement)
    {
        // Check if coordinate is within display zone
        if (coordinate.x() >= displayZone.getMinX() && coordinate.x() <= displayZone.getMaxX() &&
            coordinate.y() >= displayZone.getMinY() && coordinate.y() <= displayZone.getMaxY() &&
            coordinate.z() >= displayZone.getMinZ() && coordinate.z() <= displayZone.getMaxZ())
        {
            addElement.accept(coordinate);
        }
    }

    /**
     * Create a {@link Consumer} that adds a {@link FakeBlockElement} at a terrain-snapped location (not exact coordinates).
     * This is used for most visualization types to ensure blocks appear at visible surface levels.
     *
     * @param fakeData the fake {@link BlockData}
     * @return the function for placing a fake block at a terrain-snapped location
     */
    private @NotNull Consumer<@NotNull IntVector> addBlockElement(@NotNull BlockData fakeData, @NotNull VisualizationType type)
    {
        return vector -> {
            Block visibleLocation = getVisibleLocation(vector);
            IntVector location = new IntVector(visibleLocation);
            elements.add(new FakeBlockElement(location, visibleLocation.getBlockData(), fakeData));
            onElementAdded(location, fakeData, type);
        };
    }

    /**
     * Create a {@link Consumer} that adds a {@link FakeBlockElement} exactly at the given {@link IntVector}
     * coordinate without searching for a nearby visible ground block. This is used for 3D subdivision corners
     * so they are highlighted even when floating in air.
     *
     * @param fakeData the fake {@link BlockData}
     * @return the function for placing a fake block at the exact location
     */
    private @NotNull Consumer<@NotNull IntVector> addExactBlockElement(
            @NotNull BlockData fakeData,
            @NotNull VisualizationType type)
    {
        return vector -> {
            Block exactLocation = vector.toBlock(world);
            IntVector location = new IntVector(exactLocation);
            elements.add(new FakeBlockElement(location, exactLocation.getBlockData(), fakeData));
            onElementAdded(location, fakeData, type);
        };
    }

    /**
     * Hook for subclasses to react when a fake block element is queued for display.
     *
     * @param location the snapped block location for the element
     * @param fakeData the block data that will be sent to players
     * @param type the visualization type associated with the element
     */
    protected void onElementAdded(@NotNull IntVector location, @NotNull BlockData fakeData, @NotNull VisualizationType type) {
        // Default implementation does nothing.
    }

    /**
     * Find a location that should be visible to players. This causes the visualization to "cling" to the ground.
     *
     * @param vector the {@link IntVector} of the display location
     * @return the located {@link Block}
     */
    protected @NotNull Block getVisibleLocation(@NotNull IntVector vector)
    {
        Block start = vector.toBlock(world);
        return snapToSurface(start);
    }

    private @NotNull Block snapToSurface(@NotNull Block start)
    {
        int maxY = world.getMaxHeight() - 1;
        int minY = world.getMinHeight();

        Block column = start;

        // Step upward until we reach an open cell (air/liquid depending on context).
        while (!isTransparent(column) && column.getY() < maxY)
        {
            column = column.getRelative(BlockFace.UP);
        }

        Block current = column;
        Block lastTransparent = column;
        boolean inLiquidColumn = false;
        Block firstLiquid = null;
        Block firstWater = null;
        Block seabed = null;

        while (current.getY() >= minY)
        {
            // Check for special snap overrides first
            SnapOverride override = SnapOverrideHelper.resolve(current, waterTransparent);
            if (override != null)
            {
                switch (override)
                {
                    case SELF:
                        return current;
                    case ABOVE:
                        Block above = current.getRelative(BlockFace.UP);
                        if (above.getY() <= maxY)
                        {
                            return above;
                        }
                        return current;
                    case TWO_ABOVE:
                        Block twoAbove = current.getRelative(BlockFace.UP, 2);
                        if (twoAbove.getY() <= maxY)
                        {
                            return twoAbove;
                        }
                        return current.getRelative(BlockFace.UP);
                    case COLUMN_SURFACE:
                        // For lava, treat as surface liquid
                        if (!waterTransparent)
                        {
                            return current;
                        }
                        break;
                    case COLUMN_SEABED:
                        // Continue to find seabed
                        break;
                }
            }

            if (isLiquidLike(current))
            {
                if (!inLiquidColumn)
                {
                    inLiquidColumn = true;
                    firstLiquid = current;
                    if (isPureWater(current))
                    {
                        firstWater = current;
                    }
                }

                if (firstWater == null && isPureWater(current))
                {
                    firstWater = current;
                }

                current = current.getRelative(BlockFace.DOWN);
                continue;
            }

            if (isTransparent(current))
            {
                if (isStairOrSlab(current))
                {
                    return current;
                }

                lastTransparent = current;
                current = current.getRelative(BlockFace.DOWN);
                continue;
            }

            if (isStairOrSlab(current))
            {
                return current;
            }

            if (!inLiquidColumn)
            {
                return current;
            }

            seabed = current;
            break;
        }

        if (!waterTransparent)
        {
            if (firstWater != null)
            {
                return firstWater;
            }

            if (firstLiquid != null)
            {
                return firstLiquid;
            }

            return lastTransparent;
        }

        if (seabed != null)
        {
            return seabed;
        }

        return lastTransparent;
    }

    /**
     * Helper method for determining if a {@link Block} is transparent from the top down.
     *
     * @param block the {@code Block}
     * @return true if transparent
     */
    protected boolean isTransparent(@NotNull Block block)
    {
        if (isLiquidLike(block))
        {
            return waterTransparent;
        }

        Material blockMaterial = block.getType();

        // Check if this block has a special snap override - if so, treat as opaque
        SnapOverride override = SnapOverrideHelper.resolve(block, waterTransparent);
        if (override != null)
        {
            return false;
        }

        // Custom per-material definitions.
        if (blockMaterial == Material.SNOW)
        {
            return false;
        }

        if (isStairOrSlab(block))
            return false;

        if (blockMaterial.isAir()
                || Tag.FENCES.isTagged(blockMaterial)
                || Tag.FENCE_GATES.isTagged(blockMaterial)
                || Tag.SIGNS.isTagged(blockMaterial)
                || Tag.WALLS.isTagged(blockMaterial)
                || Tag.WALL_SIGNS.isTagged(blockMaterial))
            return true;

        return block.getType().isTransparent();
    }

    private boolean isStairOrSlab(@NotNull Block block)
    {
        BlockData data = block.getBlockData();
        if (data instanceof org.bukkit.block.data.type.Slab || data instanceof org.bukkit.block.data.type.Stairs)
        {
            return true;
        }

        return isStairOrSlab(block.getType());
    }

    private boolean isStairOrSlab(@NotNull Material material)
    {
        if (Tag.SLABS.isTagged(material) || Tag.STAIRS.isTagged(material))
        {
            return true;
        }

        String name = material.name();
        return name.contains("SLAB") || name.contains("STAIRS") || name.contains("STEP");
    }

    private boolean isPureWater(@NotNull Block block)
    {
        return block.getType() == Material.WATER;
    }

}
