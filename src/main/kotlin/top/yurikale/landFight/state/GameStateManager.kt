package top.yurikale.landFight.state

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.CompassMeta
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.team.TeamColor

class GameStateManager(private val plugin: LandFight) {
    fun isSameBlockPos(loc1: org.bukkit.Location, loc2: org.bukkit.Location): Boolean {
        return loc1.world?.name == loc2.world?.name &&
                loc1.blockX == loc2.blockX &&
                loc1.blockY == loc2.blockY &&
                loc1.blockZ == loc2.blockZ
    }

    // 默认状态为大厅
    var currentState: GameState = GameState.LOBBY
        private set

    var bases: List<org.bukkit.Location>? = null

    fun switchState(newState: GameState) {
        if (currentState != GameState.LOBBY && currentState == newState) return
        currentState = newState

        when (newState) {
            GameState.LOBBY -> {
                plugin.logger.info("Game state is LOBBY, waiting for player to join...")
                // 销毁残留大厅倒计时
                plugin.lobbyCountdownTask?.cancel()
                plugin.lobbyCountdownTask = null

                var waitTime = plugin.lobbyWaitSecond
                val runnable = object : org.bukkit.scheduler.BukkitRunnable() {
                    override fun run() {
                        val onlineCount = org.bukkit.Bukkit.getOnlinePlayers().size
                        // 人数不足，重置倒计时
                        if (onlineCount < plugin.minStartPlayer) {
                            waitTime = plugin.lobbyWaitSecond
                            return
                        }
                        waitTime--
                        // 每10秒广播一次倒计时
                        if (waitTime > 0 && waitTime % 10 == 0) {
                            org.bukkit.Bukkit.broadcastMessage("§e【大厅提示】§a 当前在线§e ${onlineCount}§a人，§e${waitTime}§a 秒后自动开战！")
                        }
                        // 倒计时结束自动开局
                        if (waitTime <= 0) {
                            this.cancel()
                            plugin.lobbyCountdownTask = null
                            org.bukkit.Bukkit.broadcastMessage("§a§l【系统】人数达标，自动开启战场！")
                            switchState(GameState.IN_GAME)
                        }
                    }
                }
                // 先保存runnable实例，再启动任务（不接收返回的BukkitTask）
                plugin.lobbyCountdownTask = runnable
                runnable.runTaskTimer(plugin, 0L, 20L)
            }

            GameState.IN_GAME -> {
                plugin.logger.info("Game state is IN_GAME, initializing battlefield...")

                plugin.teamManager.assignTeam()

                val battleWorld = plugin.worldManager.createBattleWorld()

                if (battleWorld != null) {
                    org.bukkit.Bukkit.broadcastMessage("游戏即将开始！")
                    plugin.structurePlacer.spawnAllBases(battleWorld, 15) { baseLocations ->
                        plugin.mapManager.initGameMap()
                        org.bukkit.Bukkit.getScheduler().runTask(plugin, Runnable {
                            try {
                                bases = baseLocations
                                plugin.logger.info("Battle world spawned!")

                                val shuffledBases = baseLocations.shuffled()
                                val redBaseSpawn = shuffledBases[0]
                                val blueBaseSpawn = shuffledBases[1]

                                val redBase = plugin.structurePlacer.activeBases.values.find { isSameBlockPos(it.location, redBaseSpawn) }
                                val blueBase = plugin.structurePlacer.activeBases.values.find { isSameBlockPos(it.location, blueBaseSpawn) }

                                if (redBase != null && blueBase != null) {
                                    redBase.ownerTeam = TeamColor.RED
                                    blueBase.ownerTeam = TeamColor.BLUE

                                    plugin.teamManager.teamsCapitals[TeamColor.RED] = redBase.location
                                    plugin.teamManager.teamsCapitals[TeamColor.BLUE] = blueBase.location

                                    // 刷新大本营专属视觉（羊、名字、玻璃羊毛）
                                    plugin.structurePlacer.refreshBaseVisual(redBase, isCapital = true)
                                    plugin.structurePlacer.refreshBaseVisual(blueBase, isCapital = true)
                                }

                                plugin.logger.info("Base ready! (3/3)")

                                for (player in org.bukkit.Bukkit.getOnlinePlayers()) {
                                    player.sendMessage("战场已就绪！正在空降中...")

                                    val spawnLoc = org.bukkit.Location(battleWorld, 0.0, 150.0, 0.0)
                                    player.teleport(
                                        plugin.teamManager.teamsCapitals[plugin.teamManager.getPlayerTeam(
                                            player
                                        )]?.clone()?.add(0.0, 3.0, 0.0) ?: spawnLoc
                                    )

                                    player.gameMode = org.bukkit.GameMode.SURVIVAL

                                    // distribute basic tools pack
                                    player.inventory.addItem(
                                        org.bukkit.inventory.ItemStack(
                                            org.bukkit.Material.BREAD,
                                            16
                                        )
                                    )

                                    player.inventory.addItem(
                                        org.bukkit.inventory.ItemStack(
                                            org.bukkit.Material.OAK_BOAT,
                                            1
                                        )
                                    )
                                    // ...

                                    plugin.mapManager.giveMapToPlayer(player)

                                }
                                plugin.currentGameTask = GameTask(plugin)
                                plugin.currentGameTask?.runTaskTimer(plugin, 0L, 20L)
                            } catch (e: Exception) {
                                plugin.logger.severe { "分配大本营时发生崩溃！" }
                                e.printStackTrace()
                            }
                        })
                    }
                } else {
                    plugin.logger.severe("Could not create battle world!")
                    switchState(GameState.LOBBY)
                }

            }
            GameState.RESET -> {
                plugin.logger.info("Game state is RESET, resetting battlefield...")

                plugin.currentGameTask?.cancel()
                plugin.currentGameTask = null

                plugin.sidebarManager.removeSidebar()

                val lobbyWorld = org.bukkit.Bukkit.getWorlds()[0]
                val lobbySpawn = lobbyWorld.spawnLocation

                for (player in org.bukkit.Bukkit.getOnlinePlayers()) {
                    player.teleport(lobbySpawn)

                    player.gameMode = org.bukkit.GameMode.ADVENTURE

                    player.inventory.clear()
                    player.foodLevel = 20
                    player.health = (player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20) as Double

                    player.setDisplayName(player.name)
                    player.setPlayerListName(player.name)

                    for (effect in player.activePotionEffects) {
                        player.removePotionEffect(effect.type)
                    }


                    val advancementIterator = org.bukkit.Bukkit.getServer().advancementIterator()
                    while (advancementIterator.hasNext()) {
                        val advancement = advancementIterator.next()
                        val progress = player.getAdvancementProgress(advancement)
                        // 撤销该成就下所有已解锁的条件
                        for (criteria in progress.awardedCriteria) {
                            progress.revokeCriteria(criteria)
                        }
                    }

                    player.sendMessage("§a§l[系统] §f游戏已结束")
                }

                plugin.teamManager.playerTeams.clear()
                plugin.teamManager.teamsCapitals.clear()

                plugin.worldManager.resetBattleWorld()

                plugin.teamManager.clearScoreboardTeams()

                // 清理战场地图
                plugin.mapManager.clearMap()
                switchState(GameState.LOBBY)
            }
        }
    }
}

