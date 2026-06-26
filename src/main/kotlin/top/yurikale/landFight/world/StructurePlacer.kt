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

    data class GridCell(val col: Int, val row: Int)

    fun spawnAllBases(world: World, count: Int = 15, onComplete: (List<Location>) -> Unit) {
        plugin.logger.info("Started to smoothly spawn all bases for ${world.name}, target count: $count.")
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

        // 1. 初始化任务池
        val allCells = mutableListOf<GridCell>()
        for (c in 0 until 6) {
            for (r in 0 until 6) {
                allCells.add(GridCell(c, r))
            }
        }
        allCells.shuffle(random)
        val targetCells = allCells.take(count).toMutableList()

        val generatedLocations = mutableListOf<Location>()

        // 状态控制器
        var spawned = 0
        var loadingChunks = 0
        // 使用线程安全的队列，存放“区块已加载完毕，等待放置建筑”的坐标
        val readyQueue = java.util.concurrent.ConcurrentLinkedQueue<Triple<GridCell, Int, Int>>()

        // 2. 核心调度器：每 2 ticks (0.1秒) 执行一次。彻底将运算压力分摊到时间轴上
        object : org.bukkit.scheduler.BukkitRunnable() {
            override fun run() {
                // 【完成判定】
                if (spawned >= count) {
                    plugin.logger.info("All base initialized smoothly! Total spawned: ${generatedLocations.size}.")
                    destroyProgressBar()
                    onComplete(generatedLocations)
                    this.cancel() // 任务完成，自我销毁
                    return
                }

                // 【消费者】：如果队列里有准备好的地块，就进行重度方块放置
                // 每次 tick 只 pop 出 1 个来生成，确保主线程毫无压力
                val readyItem = readyQueue.poll()
                if (readyItem != null) {
                    val (cellTask, randomX, randomZ) = readyItem

                    // 在主线程安全地获取高度和方块类型
                    val highestBlock = world.getHighestBlockAt(randomX, randomZ)
                    val highestY = highestBlock.y
                    val material = highestBlock.type

                    val isOnLeaves = Tag.LEAVES.isTagged(material)
                    val isOnLogs = Tag.LOGS.isTagged(material)
                    val isOnBamboo = material == Material.BAMBOO || material.name.contains("BAMBOO")

                    if (isOnLeaves || isOnLogs || isOnBamboo) {
                        plugin.logger.info("Skipped spawn at X:$randomX Z:$randomZ due to invalid surface: $material")
                        targetCells.add(cellTask) // 失败，将网格任务塞回池子尾部重新派发
                        return // 结束本次 tick，下个 tick 继续
                    }

                    try {
                        val targetLocation = Location(world, randomX.toDouble(), highestY.toDouble(), randomZ.toDouble())

                        // 重度的方块操作
                        cleanFoundation(world, randomX, highestY, randomZ)
                        buildFoundation(world, randomX, highestY, randomZ)
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
                            s.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 50.0
                            s.health = 50.0
                        }

                        val sheepLoc = spawnLoc.block.location
                        listOf(Location(world, sheepLoc.x, sheepLoc.y + 1, sheepLoc.z)).forEach { it.block.type = Material.GRAY_STAINED_GLASS }

                        // 绝对有序的 ID 赋予
                        val newBase = Base(
                            id = spawned,
                            location = coreLocation,
                            ownerTeam = TeamColor.NEUTRAL,
                            sheepEntityId = sheep.uniqueId
                        )
                        activeBases[newBase.id] = newBase
                        coreLocation.block.type = Material.GRAY_WOOL

                        // 触发异步扫描
                        plugin.industryManager.scanEnvironment(newBase)

                        spawned++
                        generatedLocations.add(coreLocation)
                        updateProgressBar(spawned, count)
                        plugin.logger.info("Success generated base at [${randomX}, ${highestY}, ${randomZ}] (ID: ${newBase.id})")
                    } catch (e: Exception) {
                        plugin.logger.warning("Failed spawn at X:$randomX Z:$randomZ, will retry. Error: ${e.message}")
                        targetCells.add(cellTask)
                    }

                } else {
                    // 【生产者】：如果没有排队等待放置的任务，就去请求加载新区块
                    // 维持最大并发加载数为 3，避免阻塞 IO
                    if (targetCells.isNotEmpty() && loadingChunks < 3) {
                        val cellTask = targetCells.removeAt(0)
                        loadingChunks++

                        val minX = -750 + cellTask.col * 250
                        val minZ = -750 + cellTask.row * 250
                        val randomX = minX + random.nextInt(250 - 15)
                        val randomZ = minZ + random.nextInt(250 - 15)
                        val chunkX = randomX shr 4
                        val chunkZ = randomZ shr 4

                        // 异步向底层请求区块
                        world.getChunkAtAsync(chunkX, chunkZ).thenAccept { _ ->
                            // 加载完成后，将坐标扔进队列，等待主线程消费者获取
                            readyQueue.offer(Triple(cellTask, randomX, randomZ))
                            loadingChunks--
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 2L) // 2 Tick 的执行间隔，丝滑不掉帧
    }

    private fun cleanFoundation(world:World, startX:Int, startY:Int, startZ:Int) {
        for (x in startX - 1..startX + 15) {
            for (y in startY + 1..startY + 21) {
                for (z in startZ - 1..startZ + 15) {
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

    fun getBaseAtPlayer(loc: org.bukkit.Location): Base? {
        if (loc.world?.name != plugin.worldManager.gameWorldName) return null
        val px = loc.blockX
        val py = loc.blockY
        val pz = loc.blockZ

        // 1. 优先通过你的 AABB 边界精确检测 (250x250 内的建筑区域)
        // 允许适当往外宽限 3 格作为“进入据点”的提示缓冲区
        allBaseBounds.forEachIndexed { index, bound ->
            if (px in (bound.minX - 3)..(bound.maxX + 3) &&
                py in (bound.minY - 2)..(bound.maxY + 5) &&
                pz in (bound.minZ - 3)..(bound.maxZ + 3)
            ) {
                // 通过索引或坐标映射找到对应的 Base，最稳妥的是遍历 activeBases
                return activeBases[index]
            }
        }
        return null
    }

    /**
     * 向下平滑补充泥土地基，直到遇到实体方块或水
     */
    private fun buildFoundation(world: World, startX: Int, startY: Int, startZ: Int) {
        for (x in startX..startX + 14) {
            for (z in startZ..startZ + 14) {
                var currentY = startY - 1

                while (currentY > world.minHeight) {
                    val block = world.getBlockAt(x, currentY, z)
                    val type = block.type

                    if (type == Material.WATER || type == Material.LAVA || type.isSolid) {
                        break
                    }

                    block.type = Material.DIRT
                    currentY--
                }
            }
        }
    }
}