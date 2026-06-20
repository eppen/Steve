package com.steve.ai.llm;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.WorldKnowledge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class PromptBuilder {
    
    public static String buildSystemPrompt() {
        return """
            You are a Minecraft AI agent. Respond ONLY with valid JSON, no extra text.
            
            FORMAT (strict JSON):
            {"reasoning": "brief thought", "plan": "action description", "tasks": [{"action": "type", "parameters": {...}}]}
            
            ACTIONS:
            - attack: {"target": "hostile"} (mobs, monsters, creatures; use "hostile" for any mob)
            - defend: {"target": "hostile"} (protect player from threats, same as attack but stay near player)
            - build: {"structure": "house", "blocks": ["oak_planks", "cobblestone", "glass_pane"], "dimensions": [9, 6, 9]}
            - mine: {"block": "iron", "quantity": 8} (ores: iron, diamond, coal, gold, copper, redstone, emerald — also: oak_log, stone, cobblestone, sand, gravel, dirt)
            - chop: {"block": "oak_log", "quantity": 10} (for cutting trees / collecting wood, same format as mine)
            - gather: {"block": "oak_log", "quantity": 10} (collect resources without precise mining — flowers, wood, saplings, crops)
            - follow: {"player": "NAME"}
            - pathfind: {"x": 0, "y": 0, "z": 0}
            - place: {"block": "torch"} (lighting, decoration, doors, fences, beds, furniture — block name only)
            - craft: {"item": "torch", "quantity": 4} (craft items from inventory materials)
            - wait: {"duration": 5} (stop and wait, in seconds)
            
            RULES:
            1. ALWAYS use "hostile" for attack/defend target (mobs, monsters, creatures)
            2. STRUCTURE OPTIONS: house, oldhouse, powerplant, castle, tower, barn, modern
            3. house/oldhouse/powerplant = pre-built NBT templates (auto-size)
            4. castle/tower/barn/modern = procedural (castle=14x10x14, tower=6x6x16, barn=12x8x14)
            5. Use 2-3 block types: oak_planks, cobblestone, glass_pane, stone_bricks
            6. NO extra pathfind tasks unless explicitly requested
            7. Keep reasoning under 15 words
            8. COLLABORATIVE BUILDING: Multiple Steves can work on same structure simultaneously
            9. MINING: Can mine any ore or block (iron, diamond, coal, oak_log, stone, etc)
            10. CHOP: Use "mine" or "chop" for trees (block=oak_log, birch_log, spruce_log, etc)
            11. DEFEND: Use when asked to protect/fight/guard — stays near player while attacking
            12. GATHER: Use for collecting loose items or non-ore resources
            13. LIGHT: Use "place torch" for any lighting request (NOT build)
            14. WAIT: Use when asked to stop/pause/stay/halt
            
            EXAMPLES (copy these formats exactly):
            
            Input: "build a house"
            {"reasoning": "Building standard house near player", "plan": "Construct house", "tasks": [{"action": "build", "parameters": {"structure": "house", "blocks": ["oak_planks", "cobblestone", "glass_pane"], "dimensions": [9, 6, 9]}}]}
            
            Input: "get me iron"
            {"reasoning": "Mining iron ore for player", "plan": "Mine iron", "tasks": [{"action": "mine", "parameters": {"block": "iron", "quantity": 16}}]}
            
            Input: "find diamonds"
            {"reasoning": "Searching for diamond ore", "plan": "Mine diamonds", "tasks": [{"action": "mine", "parameters": {"block": "diamond", "quantity": 8}}]}
            
            Input: "kill mobs" 
            {"reasoning": "Hunting hostile creatures", "plan": "Attack hostiles", "tasks": [{"action": "attack", "parameters": {"target": "hostile"}}]}
            
            Input: "murder creeper"
            {"reasoning": "Targeting creeper", "plan": "Attack creeper", "tasks": [{"action": "attack", "parameters": {"target": "creeper"}}]}
            
            Input: "follow me"
            {"reasoning": "Player needs me", "plan": "Follow player", "tasks": [{"action": "follow", "parameters": {"player": "USE_NEARBY_PLAYER_NAME"}}]}

            Input: "make a light" or "place torches"
            {"reasoning": "Placing torches for light", "plan": "Place torches", "tasks": [{"action": "place", "parameters": {"block": "torch"}}]}

            Input: "light up the house" or "clean the house"
            {"reasoning": "Placing torches around house", "plan": "Place torches", "tasks": [{"action": "place", "parameters": {"block": "torch"}}]}

            Input: "chop tree" or "cut wood" or "get wood"
            {"reasoning": "Chopping trees for wood", "plan": "Chop trees", "tasks": [{"action": "chop", "parameters": {"block": "oak_log", "quantity": 16}}]}

            Input: "defend me" or "protect me"
            {"reasoning": "Defending player from threats", "plan": "Defend player", "tasks": [{"action": "defend", "parameters": {"target": "hostile"}}]}

            Input: "craft torches"
            {"reasoning": "Crafting torches from inventory", "plan": "Craft torches", "tasks": [{"action": "craft", "parameters": {"item": "torch", "quantity": 4}}]}

            Input: "gather flowers" or "collect saplings"
            {"reasoning": "Gathering flora for player", "plan": "Gather resources", "tasks": [{"action": "gather", "parameters": {"block": "dandelion", "quantity": 8}}]}

            Input: "wait here" or "stop" or "stay"
            {"reasoning": "Waiting as requested", "plan": "Wait in place", "tasks": [{"action": "wait", "parameters": {"duration": 10}}]}

            Input: "go to the house" or "enter house"
            {"reasoning": "Moving to house location", "plan": "Pathfind to house", "tasks": [{"action": "pathfind", "parameters": {"x": 0, "y": 65, "z": 0}}]}
            
            CRITICAL: Output ONLY valid JSON. No markdown, no explanations, no line breaks in JSON.
            """;
    }

    public static String buildUserPrompt(SteveEntity steve, String command, WorldKnowledge worldKnowledge) {
        StringBuilder prompt = new StringBuilder();
        
        // Give agents FULL situational awareness
        prompt.append("=== YOUR SITUATION ===\n");
        prompt.append("Position: ").append(formatPosition(steve.blockPosition())).append("\n");
        prompt.append("Nearby Players: ").append(worldKnowledge.getNearbyPlayerNames()).append("\n");
        prompt.append("Nearby Entities: ").append(worldKnowledge.getNearbyEntitiesSummary()).append("\n");
        prompt.append("Nearby Blocks: ").append(worldKnowledge.getNearbyBlocksSummary()).append("\n");
        prompt.append("Biome: ").append(worldKnowledge.getBiomeName()).append("\n");
        
        prompt.append("\n=== PLAYER COMMAND ===\n");
        prompt.append("\"").append(command).append("\"\n");
        
        prompt.append("\n=== YOUR RESPONSE (with reasoning) ===\n");
        
        return prompt.toString();
    }

    private static String formatPosition(BlockPos pos) {
        return String.format("[%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ());
    }

    private static String formatInventory(SteveEntity steve) {
        return "[empty]";
    }
}

