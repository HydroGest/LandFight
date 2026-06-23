package top.yurikale.landFight.world

import org.bukkit.Bukkit
import org.bukkit.DyeColor
import top.yurikale.landFight.LandFight
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.entity.Sheep
import org.bukkit.block.structure.Mirror
import org.bukkit.block.structure.StructureRotation
import top.yurikale.landFight.team.TeamColor
import java.io.File
import java.util.Random
import org.bukkit.boss.BossBar
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import top.yurikale.landFight.state.Base
import top.yurikale.landFight.state.NetworkGraph

class StructurePlacer(private val plugin: LandFight) {

    // 在 StructurePlacer.kt 或者 StateManager.kt 里：
    val activeBases = mutableMapOf<Int, Base>() // ID -> 据点对象
    val networkGraph = NetworkGraph() // 实例化那张交通网

    // 所有据点原生建筑方块坐标字符串
    val allBaseStructureBlocks = mutableSetOf<String>()
    data class BaseStructureBound(
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val minZ: Int,
        val maxZ: Int
    )
    val allBaseBounds = mutableListOf<BaseStructureBound>()

    private var spawnProgressBar: BossBar? = null

    private fun createProgressBar(total: Int): BossBar {
        val bar = Bukkit.createBossBar(
            "据点生成中 0/$total",
            BarColor.GREEN,
            BarStyle.SOLID
        )
        Bukkit.getOnlinePlayers().forEach { bar.addPlayer(it) }
        return bar
    }

    private fun updateProgressBar(current: Int, total: Int) {
        val bar = spawnProgressBar ?: return
        bar.setTitle("据点生成中 $current/$total")
        bar.progress = current.toDouble() / total.toDouble()
        // 同步给新上线玩家
        Bukkit.getOnlinePlayers().forEach { if (!bar.players.contains(it)) bar.addPlayer(it) }
    }

    fun destroyProgressBar() {
        spawnProgressBar?.removeAll()
        spawnProgressBar = null
    }

    fun getCapturedNumber(color: TeamColor): Int {
        return plugin.structurePlacer.activeBases.values.count { base ->
            base.ownerTeam == color
        }
    }

    private fun blockPosKey(x: Int, y: Int, z: Int): String = "$x,$y,$z"

    fun spawnAllBases(world: World, count: Int = 15, onComplete: (List<Location>) -> Unit) { // 这里默认值改为了15
        plugin.logger.info("Started to async spawn all bases for ${world.name}, target count: $count.")
        activeBases.clear()
        allBaseStructureBlocks.clear()
        allBaseBounds.clear()

        destroyProgressBar()
        spawnProgressBar = createProgressBar(count)

        val nbtFile = File(plugin.dataFolder, "base_tower.nbt")
        if (!nbtFile.exists()) {
            plugin.logger.warning("Could not find nbt file for base tower. Path: ${nbtFile.absolutePath}")
            destroyProgressBar()
            return
        }
        val structureManager = Bukkit.getStructureManager()
        val structure = structureManager.loadStructure(nbtFile)
        val random = Random()

        val generatedLocations = mutableListOf<Location>()
        var spawned = 0
        var pendingTask = 0
        val maxConcurrent = 8

        var isSpawnFinish = false

        fun checkAllDone() {
            synchronized(generatedLocations) {
                if (isSpawnFinish && pendingTask <= 0) {
                    plugin.logger.info("All base initialized, total spawned: ${generatedLocations.size}.")
                    destroyProgressBar()
                    onComplete(generatedLocations)
                }
            }
        }

        fun trySpawnOne() {
            synchronized(generatedLocations) {
                if (isSpawnFinish) return
                if (pendingTask >= maxConcurrent) return
            }

            pendingTask++
            val randomX = random.nextInt(1490) - 745
            val randomZ = random.nextInt(1490) - 745
            val chunkX = randomX shr 4
            val chunkZ = randomZ shr 4

            world.getChunkAtAsync(chunkX, chunkZ).thenAccept { chunk ->
                var success = false
                try {
                    synchronized(generatedLocations) {
                        if (isSpawnFinish) return@thenAccept
                    }

                    val highestBlock = world.getHighestBlockAt(randomX, randomZ)
                    val highestY = highestBlock.y
                    val material = highestBlock.type

                    val isOnLeaves = Tag.LEAVES.isTagged(material)
                    val isOnLogs = Tag.LOGS.isTagged(material)
                    val isOnBamboo = material == Material.BAMBOO || material.name.contains("BAMBOO")

                    if (isOnLeaves || isOnLogs || isOnBamboo) {
                        plugin.logger.info("Skipped spawn at X:$randomX Z:$randomZ due to invalid surface: $material")
                        // 直接 return 跳出 try 块。
                        // 由于 finally 块的存在，程序会自动触发重试
                        return@thenAccept
                    }

                    val targetLocation = Location(world, randomX.toDouble(), highestY.toDouble(), randomZ.toDouble())
                    targetLocation.chunk.load(true)

                    cleanFoundation(world, randomX, highestY, randomZ)
                    structure.place(targetLocation, true, StructureRotation.NONE, Mirror.NONE, -1, 1.0f, random)

                    val structX = targetLocation.blockX
                    val structY = targetLocation.blockY + 1
                    val structZ = targetLocation.blockZ
                    allBaseBounds.add(
                        BaseStructureBound(
                            minX = structX, maxX = structX + 14,
                            minY = structY, maxY = structY + 20,
                            minZ = structZ, maxZ = structZ + 14
                        )
                    )
                    for (x in structX..structX + 14) {
                        for (y in structY..structY + 21) {
                            for (z in structZ..structZ + 14) {
                                if (world.getBlockAt(x, y, z).type != Material.AIR) {
                                    allBaseStructureBlocks.add(blockPosKey(x, y, z))
                                }
                            }
                        }
                    }

                    val coreLocation = targetLocation.clone().add(6.0, 4.0, 6.0)
                    val spawnLoc = coreLocation.clone().add(0.5, 1.0, 0.5)
                    val sheep = world.spawn(spawnLoc, org.bukkit.entity.Sheep::class.java) { s ->
                        s.setAI(false)
                        s.color = org.bukkit.DyeColor.GRAY
                        s.customName = "§7[中立据点]"
                        s.isCustomNameVisible = true
                    }

                    val sheepLoc = spawnLoc.block.location
                    val glassPositions = listOf(Location(world, sheepLoc.x, sheepLoc.y + 1, sheepLoc.z))
                    glassPositions.forEach { it.block.type = Material.GRAY_STAINED_GLASS }

                    val newBase = Base(
                        id = spawned,
                        location = coreLocation,
                        ownerTeam = TeamColor.NEUTRAL,
                        sheepEntityId = sheep.uniqueId
                    )
                    activeBases[newBase.id] = newBase
                    coreLocation.block.type = Material.GRAY_WOOL

//                    success = true
                    synchronized(generatedLocations) {
                        if (spawned < count) {
                            spawned++
                            generatedLocations.add(coreLocation)
                            updateProgressBar(spawned, count)
                            if (spawned >= count) {
                                isSpawnFinish = true
                            }
                        }
                    }
                    plugin.logger.info("Success generated base at [${randomX}, ${highestY}, ${randomZ}]")
                } catch (e: Exception) {
                    plugin.logger.warning("Failed spawn at X:$randomX Z:$randomZ, will retry. Error: ${e.message}")
                } finally {
                    synchronized(generatedLocations) {
                        pendingTask--
                    }
                    synchronized(generatedLocations) {
                        // 只要没达到指定数量，就会继续尝试生成
                        if (!isSpawnFinish) {
                            trySpawnOne()
                        }
                    }
                    checkAllDone()
                }
            }
        }

        repeat(maxConcurrent) {
            trySpawnOne()
        }
    }

    private fun cleanFoundation(world:World, startX:Int, startY:Int, startZ:Int) {
        for (x in startX..startX + 14) {
            for (y in startY..startY + 21) {
                for (z in startZ..startZ + 14) {
                    world.getBlockAt(x, y, z).type = Material.AIR
                }
            }
        }
    }

    /**
     * 刷新一个据点完整视觉：羊染色、名字、底座羊毛、头顶染色玻璃
     * @param base 目标据点
     * @param isCapital 是否为本队大本营（区分样式）
     */
    fun refreshBaseVisual(base: Base, isCapital: Boolean) {
        val sheep = plugin.server.getEntity(base.sheepEntityId ?: return) as? Sheep ?: return
        val owner = base.ownerTeam ?: TeamColor.NEUTRAL
        val maxHp = sheep.getAttribute(Attribute.MAX_HEALTH)?.value ?: 8.0
        val currentHp = sheep.health
        // 保留1位小数血量显示
        val hpText = " §6HP:${String.format("%.1f", currentHp)}/${String.format("%.1f", maxHp)}"

        // 1. 修改羊颜色、名称（拼接血量）
        when(owner) {
            TeamColor.NEUTRAL -> {
                sheep.color = DyeColor.GRAY
                sheep.customName = "§7[中立据点]$hpText"
            }
            TeamColor.RED -> {
                sheep.color = DyeColor.RED
                sheep.customName = if(isCapital) "§c■ 红队【大本营】■$hpText" else "§c■ 红队据点 ■$hpText"
            }
            TeamColor.BLUE -> {
                sheep.color = DyeColor.BLUE
                sheep.customName = if(isCapital) "§9■ 蓝队【大本营】■$hpText" else "§9■ 蓝队据点 ■$hpText"
            }
        }
        sheep.isCustomNameVisible = true

        // 2. 据点底座羊毛
        val woolLoc = base.location
        val woolBlock = woolLoc.block
        // 3. 据点13格高空玻璃标识
        val glassHighLoc = woolLoc.clone().add(0.0,13.0,0.0)
        val glassHighBlock = glassHighLoc.block
        // 4. 羊头顶1格染色玻璃
        val sheepTopLoc = sheep.location.block.location.add(0.0,1.0,0.0)
        val sheepTopBlock = sheepTopLoc.block

        when(owner) {
            TeamColor.NEUTRAL -> {
                woolBlock.type = Material.GRAY_WOOL
                glassHighBlock.type = Material.AIR
                sheepTopBlock.type = Material.GRAY_STAINED_GLASS
            }
            TeamColor.RED -> {
                woolBlock.type = Material.RED_WOOL
                glassHighBlock.type = Material.RED_STAINED_GLASS
                sheepTopBlock.type = Material.RED_STAINED_GLASS
            }
            TeamColor.BLUE -> {
                woolBlock.type = Material.BLUE_WOOL
                glassHighBlock.type = Material.BLUE_STAINED_GLASS
                sheepTopBlock.type = Material.BLUE_STAINED_GLASS
            }
        }
    }

    /** 根据据点ID获取是否是某队伍当前大本营 */
    fun isBaseCapital(baseId: Int): Boolean {
        val base = activeBases[baseId] ?: return false
        val redCap = plugin.teamManager.teamsCapitals[TeamColor.RED]
        val blueCap = plugin.teamManager.teamsCapitals[TeamColor.BLUE]
        return (redCap != null && isSameBlockPos(base.location, redCap))
                || (blueCap != null && isSameBlockPos(base.location, blueCap))
    }

    private fun isSameBlockPos(loc1: Location, loc2: Location): Boolean {
        return loc1.world?.name == loc2.world?.name
                && loc1.blockX == loc2.blockX
                && loc1.blockY == loc2.blockY
                && loc1.blockZ == loc2.blockZ
    }
}