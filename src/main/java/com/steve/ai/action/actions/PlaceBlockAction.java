package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class PlaceBlockAction extends BaseAction {
    private Block blockToPlace;
    private BlockPos targetPos;
    private int ticksRunning;
    private int placementsDone;
    private static final int MAX_TICKS = 600;       // 30 seconds for multiple placements
    private static final int MAX_PLACEMENTS = 8;     // place up to 8 blocks

    public PlaceBlockAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        String blockName = task.getStringParameter("block");
        boolean hasExplicitCoords = task.hasParameters("x");
        int x = task.getIntParameter("x", Integer.MIN_VALUE);
        int y = task.getIntParameter("y", Integer.MIN_VALUE);
        int z = task.getIntParameter("z", Integer.MIN_VALUE);

        ticksRunning = 0;
        placementsDone = 0;

        blockToPlace = parseBlock(blockName);

        if (blockToPlace == null || blockToPlace == Blocks.AIR) {
            result = ActionResult.failure("Invalid block type: " + blockName);
            return;
        }

        if (hasExplicitCoords) {
            targetPos = new BlockPos(x, y, z);
        } else {
            // Auto-pick a position near the Steve (at head/eye level against a wall, or ground level)
            targetPos = findPlacementPosition();
            if (targetPos == null) {
                result = ActionResult.failure("No suitable position to place " + blockName);
                return;
            }
        }

        com.steve.ai.SteveMod.LOGGER.info("PlaceBlockAction: placing {} at {} (explicit coords: {})",
            blockName, targetPos, hasExplicitCoords);
    }

    /**
     * Find a suitable position near the Steve for placing a block.
     * Prefers ground level near Steve's position.
     */
    private BlockPos findPlacementPosition() {
        BlockPos stevePos = steve.blockPosition();

        // Search in a 5x5 area around Steve at ground/eye level
        for (int dy = 0; dy <= 3; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos checkPos = stevePos.offset(dx, dy, dz);
                    BlockState state = steve.level().getBlockState(checkPos);
                    BlockPos belowPos = checkPos.below();
                    BlockState belowState = steve.level().getBlockState(belowPos);

                    // Can place if position is air and there's solid ground below
                    if (state.isAir() && belowState.isSolid()) {
                        return checkPos;
                    }
                }
            }
        }
        return null;
    }

    @Override
    protected void onTick() {
        ticksRunning++;

        if (ticksRunning > MAX_TICKS || placementsDone >= MAX_PLACEMENTS) {
            result = placementsDone > 0
                ? ActionResult.success("Placed " + placementsDone + " " + blockToPlace.getName().getString() + " blocks")
                : ActionResult.failure("Place block timeout");
            return;
        }

        // Move closer if needed
        if (!steve.blockPosition().closerThan(targetPos, 4.0)) {
            steve.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 1.0);
            return;
        }

        BlockState currentState = steve.level().getBlockState(targetPos);
        if (!currentState.isAir()) {
            // Position already filled - find a new spot and try again next tick
            targetPos = findPlacementPosition();
            if (targetPos == null) {
                result = placementsDone > 0
                    ? ActionResult.success("Placed " + placementsDone + " " + blockToPlace.getName().getString() + " blocks")
                    : ActionResult.failure("No suitable empty position found");
            }
            return;
        }

        steve.level().setBlock(targetPos, blockToPlace.defaultBlockState(), 3);
        placementsDone++;
        com.steve.ai.SteveMod.LOGGER.info("Steve '{}' placed {} #{}/{} at {}",
            steve.getSteveName(), blockToPlace.getName().getString(),
            placementsDone, MAX_PLACEMENTS, targetPos);

        // Find next position for next tick (don't set result yet!)
        targetPos = findPlacementPosition();
        if (targetPos == null && placementsDone > 0) {
            result = ActionResult.success("Placed " + placementsDone + " " + blockToPlace.getName().getString() + " blocks");
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Place " + blockToPlace.getName().getString() + " at " + targetPos;
    }

    private Block parseBlock(String blockName) {
        blockName = blockName.toLowerCase().replace(" ", "_");
        if (!blockName.contains(":")) {
            blockName = "minecraft:" + blockName;
        }
        ResourceLocation resourceLocation = new ResourceLocation(blockName);
        return BuiltInRegistries.BLOCK.get(resourceLocation);
    }
}

