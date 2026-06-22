package top.yurikale.landFight.world

import org.bukkit.Bukkit
import  top.yurikale.landFight.LandFight
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
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


    fun location2String(location: Location): String {
        return "${location.world?.name}, ${location.x.toInt()}, ${location.y.toInt()}, ${location.z.toInt()}"
    }

    private fun blockPosKey(x: Int, y: Int, z: Int): String = "$x,$y,$z"

    fun spawnAllBases(world: World, count: Int = 100, onComplete: (List<Location>) -> Unit) {
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
        var pendingTask = 0 // 当前正在执行的异步任务数
        val maxConcurrent = 8 // 限制并发chunk加载，防止服务器炸线程

        // 内部递归生成函数
        fun trySpawnOne() {
            // 已生成足够数量，且无正在执行的异步任务 → 完成
            synchronized(generatedLocations) {
                if (spawned >= count && pendingTask <= 0) {
                    plugin.logger.info("All base initialized, total spawned: ${generatedLocations.size}.")
                    destroyProgressBar()
                    onComplete(generatedLocations)
                    return
                }
                // 达到并发上限 或 已生成足够，暂停生成
                if (pendingTask >= maxConcurrent || spawned >= count) return
            }

            pendingTask++
            // 左右各缩5：-745 ~ 744
            val randomX = random.nextInt(1490) - 745
            val randomZ = random.nextInt(1490) - 745
            val chunkX = randomX shr 4
            val chunkZ = randomZ shr 4

            world.getChunkAtAsync(chunkX, chunkZ).thenAccept { chunk ->
                try {
                    val highestBlock = world.getHighestBlockAt(randomX, randomZ)
                    val highestY = highestBlock.y
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


                    val newBaseId = spawned // 0 到 29
                    val newBase = Base(
                        id = newBaseId,
                        location = coreLocation,
                        ownerTeam = TeamColor.NEUTRAL,
                        // 羊的逻辑还没写好，暂时先留空或者继续用羊毛
                    )
                    activeBases[newBaseId] = newBase
                    coreLocation.block.type = Material.GRAY_WOOL


                    synchronized(generatedLocations) {
                        spawned++
                        generatedLocations.add(coreLocation)
                        updateProgressBar(spawned, count)
                    }
                    plugin.logger.info("Success generated base at [${randomX}, ${highestY}, ${randomZ}]")
                } catch (e: Exception) {
                    plugin.logger.warning("Failed spawn at X:$randomX Z:$randomZ, will retry")
                } finally {
                    synchronized(generatedLocations) {
                        pendingTask--
                    }
                    // 立刻再次调用，补生成缺口
                    trySpawnOne()
                }
            }
        }

        // 启动多轮生成
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
}