package top.yurikale.landFight.system

import org.bukkit.Bukkit
import org.bukkit.Material
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.state.Base
import kotlin.math.floor

class IndustryManager(private val plugin: LandFight) {
    // 配置：不同据点等级对应的每 10 秒“总方块操作数”（包含空气）
    private fun getOutputCap(level: Int): Int = when (level) {
        1 -> 10
        2 -> 20
        3 -> 40
        else -> 0
    }

    /**
     * 扫描据点周围环境，建立权重模型。
     * 在据点生成完毕后调用：plugin.industryManager.scanEnvironment(base)
     */
    fun scanEnvironment(base: Base) {
        // 建议将扫描放回主线程运行。因为去除了 getBlockState 后，单纯的 getType 性能极高。
        // 即便扫描数万个方块，耗时也仅需 1~3 毫秒，且绝对不会引发异步异常。
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val loc = base.location
            val world = loc.world ?: return@Runnable

            // 建议半径稍微调小一点，30 已经是一个直径 61 格的巨大范围了，足够覆盖整座山头或矿脉
            val radius = 30
            val heightDown = 30
            val heightUp = 20

            var validBlocks = 0 // 我们只统计实体方块，排除空气，这样算出来的比例才科学

            val counts = mutableMapOf<Material, Int>()

            for (x in -radius..radius) {
                for (z in -radius..radius) {
                    val realX = loc.blockX + x
                    val realZ = loc.blockZ + z

                    if (!world.isChunkLoaded(realX shr 4, realZ shr 4)) continue

                    for (y in -heightDown..heightUp) {
                        val realY = loc.blockY + y

                        val type = world.getType(realX, realY, realZ)

                        // 跳过空气、水和岩浆，不计入总数，防止分母过大
                        if (type.isAir || type == Material.WATER || type == Material.LAVA) continue
                        validBlocks++

                        // 映射表：把自然方块转化为工业产物
                        val targetMaterial = when (type) {
                            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE -> Material.IRON_INGOT
                            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE -> Material.GOLD_INGOT
                            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE -> Material.COAL
                            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE -> Material.DIAMOND
                            Material.SAND, Material.RED_SAND -> Material.GUNPOWDER
                            Material.GRAVEL -> Material.FLINT
                            Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG,
                            Material.JUNGLE_LOG, Material.ACACIA_LOG, Material.DARK_OAK_LOG -> Material.OAK_LOG
                            Material.STONE, Material.COBBLESTONE, Material.DEEPSLATE -> Material.COBBLESTONE
                            else -> null
                        }

                        if (targetMaterial != null) {
                            counts[targetMaterial] = counts.getOrDefault(targetMaterial, 0) + 1
                        }
                    }
                }
            }

            val balanceMultiplier = 3.0

            counts.forEach { (mat, count) ->
                // 防止 validBlocks 为 0 导致除以 0 的错误
                val rawWeight = if (validBlocks > 0) count.toDouble() / validBlocks else 0.0
                val finalWeight = (rawWeight * balanceMultiplier).coerceAtMost(1.0)
                base.resourceWeights[mat] = finalWeight
            }

            base.isScanned = true
            plugin.logger.info("据点 #${base.id} 扫描完成！共扫描到 $validBlocks 个有效方块，发现 ${counts.size} 种资源。")
        })
    }

    /**
     * 工业产出心跳
     */
    fun tickIndustry() {
        val activeBases = plugin.structurePlacer.activeBases.values

        for (base in activeBases) {
            if (!base.isScanned) continue
            val owner = base.ownerTeam ?: continue
            if (owner == top.yurikale.landFight.team.TeamColor.NEUTRAL) continue // 中立据点停工

            val outputCap = getOutputCap(base.level)

            for ((mat, weight) in base.resourceWeights) {
                // 本次应增加的量（通常带有小数）
                val production = outputCap * weight
                val currentAccumulated = base.resourceAccumulator.getOrDefault(mat, 0.0) + production

                // 取出整数部分
                val generatedItems = floor(currentAccumulated).toInt()

                if (generatedItems > 0) {
                    base.resourceStorage[mat] = base.resourceStorage.getOrDefault(mat, 0) + generatedItems
                }

                // 保留剩下的小数部分，等待下一次发酵
                base.resourceAccumulator[mat] = currentAccumulated - generatedItems
            }
        }
    }
}