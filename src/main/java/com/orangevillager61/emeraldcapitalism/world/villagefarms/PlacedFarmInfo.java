package com.orangevillager61.emeraldcapitalism.world.villagefarms;

/**
 * Stores the resolved placement info for a farm that was successfully placed.
 * Used by the village-wide water containment pass after all farms are down.
 *
 * @param originX     X origin of the placed farm
 * @param originZ     Z origin of the placed farm
 * @param placementY  the computed ground Y the farm was placed at
 * @param footprintX  X size of the farm footprint (post-rotation)
 * @param footprintZ  Z size of the farm footprint (post-rotation)
 * @param templateHeight  Y height of the placed template
 */
public record PlacedFarmInfo(int originX, int originZ, int placementY,
                              int footprintX, int footprintZ, int templateHeight) {
}
