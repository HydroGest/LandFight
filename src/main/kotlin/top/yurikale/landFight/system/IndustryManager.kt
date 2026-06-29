package top.yurikale.landFight.system

import org.bukkit.Bukkit
import org.bukkit.Material
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.state.Base
import kotlin.math.floor
import kotlin.math.log2

class IndustryManager(private val plugin: LandFight) {
    var lastCycleStartTime: Long = System.currentTimeMillis()
        private set
    private fun getOutputCap(level: Int): Int = when (level) {
        1 -> 10
        2 -> 20
        3 -> 40
        else -> 0
    }
    fun scanEnvironment(base: Base) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val loc = base.location
            val world = loc.world ?: return@Runnable
            val radius = 15
            val heightDown = 30
            val heightUp = 20
            val minCX = (loc.blockX - radius) shr 4
            val maxCX = (loc.blockX + radius) shr 4
            val minCZ = (loc.blockZ - radius) shr 4
            val maxCZ = (loc.blockZ + radius) shr 4
            for (cx in minCX..maxCX) {
                for (cz in minCZ..maxCZ) {
                    val chunk = world.getChunkAt(cx, cz)
                    if (!chunk.isLoaded) {
                        chunk.load(true)
                    }
                }
            }
            var validBlocks = 0
            val counts = mutableMapOf<Material, Double>()
            for (x in -radius..radius) {
                for (z in -radius..radius) {
                    val realX = loc.blockX + x
                    val realZ = loc.blockZ + z
                    for (y in -heightDown..heightUp) {
                        val realY = loc.blockY + y
                        val type = world.getType(realX, realY, realZ)
                        if (type.isAir || type == Material.WATER || type == Material.LAVA) continue
                        validBlocks++
                        when (type) {
                            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE ->
                                counts[Material.IRON_INGOT] = counts.getOrDefault(Material.IRON_INGOT, 0.0) + 1.0
                            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE ->
                                counts[Material.GOLD_INGOT] = counts.getOrDefault(Material.GOLD_INGOT, 0.0) + 1.0
                            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE ->
                                counts[Material.COAL] = counts.getOrDefault(Material.COAL, 0.0) + 1.0
                            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE ->
                                counts[Material.DIAMOND] = counts.getOrDefault(Material.DIAMOND, 0.0) + 1.0
                            Material.SAND, Material.RED_SAND -> {
                                counts[Material.GUNPOWDER] = counts.getOrDefault(Material.GUNPOWDER, 0.0) + 0.5
                                counts[Material.SAND] = counts.getOrDefault(Material.SAND, 0.0) + 0.5
                            }
                            Material.GRAVEL ->
                                counts[Material.FLINT] = counts.getOrDefault(Material.FLINT, 0.0) + 1.0
                            Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG,
                            Material.JUNGLE_LOG, Material.ACACIA_LOG, Material.DARK_OAK_LOG ->
                                counts[Material.OAK_LOG] = counts.getOrDefault(Material.OAK_LOG, 0.0) + 1.0
                            Material.STONE, Material.COBBLESTONE, Material.DEEPSLATE ->
                                counts[Material.COBBLESTONE] = counts.getOrDefault(Material.COBBLESTONE, 0.0) + 1.0
                            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE ->
                                counts[Material.COPPER_INGOT] = counts.getOrDefault(Material.COPPER_INGOT, 0.0) + 1.0
                            Material.GRASS_BLOCK -> {
                                counts[Material.WHEAT] = counts.getOrDefault(Material.WHEAT, 0.0) + 0.5
                                counts[Material.FEATHER] = counts.getOrDefault(Material.FEATHER, 0.0) + 0.5
                            }
                            else -> {}
                        }
                    }
                }
            }
            val logCounts = mutableMapOf<Material, Double>()
            var totalLogSum = 0.0
            counts.forEach { (mat, count) ->
                val logVal = log2(count + 2.0)
                logCounts[mat] = logVal
                totalLogSum += logVal
            }
            logCounts.forEach { (mat, logVal) ->
                val finalWeight = if (totalLogSum > 0) logVal / totalLogSum else 0.0
                base.resourceWeights[mat] = finalWeight.coerceAtMost(1.0)
            }
            base.isScanned = true
            plugin.logger.info("据点 #${base.id} 扫描完成！共扫描 $validBlocks 个有效方块，发现 ${counts.size} 种资源。")
        })
    }
    fun tickIndustry() {
        lastCycleStartTime = System.currentTimeMillis()
        val activeBases = plugin.structurePlacer.activeBases.values
        for (base in activeBases) {
            if (!base.isScanned) continue
            val owner = base.ownerTeam ?: continue
            if (owner == top.yurikale.landFight.team.TeamColor.NEUTRAL) continue
            val outputCap = getOutputCap(base.level)
            for ((mat, weight) in base.resourceWeights) {
                val production = outputCap * weight
                val currentAccumulated = base.resourceAccumulator.getOrDefault(mat, 0.0) + production
                val generatedItems = floor(currentAccumulated).toInt()
                if (generatedItems > 0) {
                    // 加入 128 上限截断
                    val currentStorage = base.resourceStorage.getOrDefault(mat, 0)
                    base.resourceStorage[mat] = (currentStorage + generatedItems).coerceAtMost(128)
                }
                base.resourceAccumulator[mat] = currentAccumulated - generatedItems
            }
            // 自动物流：转运到大本营
            if (base.isLogisticsEnabled) {
                val teamOwner = base.ownerTeam ?: continue
                val capitalLoc = plugin.teamManager.teamsCapitals[teamOwner] ?: continue
                val capitalBase = plugin.structurePlacer.activeBases.values.find {
                    plugin.stateManager.isSameBlockPos(it.location, capitalLoc)
                } ?: continue
                if (plugin.structurePlacer.networkGraph.isConnected(base.id, capitalBase.id)) {
                    val storageCopy = base.resourceStorage.toMap()
                    storageCopy.forEach { (mat, count) ->
                        if (count > 0) {
                            val finalAmount = floor(count * 0.9).toInt()
                            if (finalAmount > 0) {
                                val sourceKey = "source_${base.id}_${mat.name}"
                                capitalBase.resourceSourceMark[sourceKey] =
                                    capitalBase.resourceSourceMark.getOrDefault(sourceKey, 0) + finalAmount

                                val currentCapStorage = capitalBase.resourceStorage.getOrDefault(mat, 0)
                                capitalBase.resourceStorage[mat] = (currentCapStorage + finalAmount).coerceAtMost(128)
                            }
                        }
                    }
                    base.resourceStorage.clear()
                    base.resourceAccumulator.clear()
                } else {
                    // 如果断网，后台强行关闭自动物流
                    base.isLogisticsEnabled = false
                }
            }
            // 在线玩家热更新UI
            for (player in Bukkit.getOnlinePlayers()) {
                val topInv = player.openInventory.topInventory
                val holder = topInv.holder
                if (holder is top.yurikale.landFight.ui.IndustryMenuHolder) {
                    holder.setupMenu()
                }
            }
        }
    }
}