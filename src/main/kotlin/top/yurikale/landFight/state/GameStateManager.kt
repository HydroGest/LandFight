package top.yurikale.landFight.state

import org.bukkit.attribute.Attribute
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.team.TeamColor

class GameStateManager(private val plugin: LandFight) {
    fun isSameBlockPos(loc1: org.bukkit.Location, loc2: org.bukkit.Location): Boolean {
        return loc1.world?.name == loc2.world?.name &&
                loc1.blockX == loc2.blockX &&
                loc1.blockY == loc2.blockY &&
                loc1.blockZ == loc2.blockZ
    }

    var currentState: GameState = GameState.LOBBY
        private set

    var isPvPEnabled: Boolean = false
        internal set

    var bases: List<org.bukkit.Location>? = null

    var currentWaitTime: Int = 300
        internal set

    fun switchState(newState: GameState) {
        if (currentState != GameState.LOBBY && currentState == newState) return
        currentState = newState

        when (newState) {
            GameState.LOBBY -> {
                plugin.logger.info("Game state is LOBBY, waiting for player to join...")
                plugin.lobbyCountdownTask?.cancel()
                plugin.lobbyCountdownTask = null

                currentWaitTime = 300
                val runnable = object : org.bukkit.scheduler.BukkitRunnable() {
                    override fun run() {
                        val onlineCount = org.bukkit.Bukkit.getOnlinePlayers().size
                        if (onlineCount < plugin.minStartPlayer) {
                            currentWaitTime = 300
                            return
                        }
                        currentWaitTime--
                        if (currentWaitTime > 0 && currentWaitTime % 10 == 0) {
                            org.bukkit.Bukkit.broadcastMessage("§e【大厅提示】§a 当前在线§e ${onlineCount}§a人，§e${currentWaitTime}§a 秒后自动开战！")
                        }
                        if (currentWaitTime <= 0) {
                            this.cancel()
                            plugin.lobbyCountdownTask = null
                            org.bukkit.Bukkit.broadcastMessage("§a§l【系统】人数达标，自动开启战场！")
                            switchState(GameState.IN_GAME)
                        }
                    }
                }
                plugin.lobbyCountdownTask = runnable
                runnable.runTaskTimer(plugin, 0L, 20L)
                isPvPEnabled = false
            }

            GameState.IN_GAME -> {
                // 强制取消大厅倒计时，防止游戏开始后还在跑倒计时
                plugin.lobbyCountdownTask?.cancel()
                plugin.lobbyCountdownTask = null

                isPvPEnabled = false
                plugin.logger.info("Game state is IN_GAME, initializing battlefield...")

                plugin.teamManager.assignTeam()

                val battleWorld = plugin.worldManager.createBattleWorld()

                if (battleWorld != null) {
                    org.bukkit.Bukkit.broadcastMessage("游戏即将开始！")
                    plugin.structurePlacer.spawnAllBases(battleWorld, 15) { baseLocations ->
                        plugin.mapManager.initGameMap()
                        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                            try {
                                bases = baseLocations
                                plugin.logger.info("Battle world spawned!")
                                val shuffledBases = baseLocations.shuffled()
                                val redBaseSpawn = shuffledBases[0]
                                val blueBaseSpawn = shuffledBases[1]
                                val redBase = plugin.structurePlacer.activeBases.values.find { isSameBlockPos(it.location, redBaseSpawn) }
                                val blueBase = plugin.structurePlacer.activeBases.values.find { isSameBlockPos(it.location, blueBaseSpawn) }
                                plugin.logger.info("Base ready! (3/3)")
                                if (redBase != null && blueBase != null) {
                                    org.bukkit.Bukkit.getScheduler().runTask(plugin, Runnable {
                                        battleWorld.getChunkAt(redBaseSpawn)
                                        battleWorld.getChunkAt(blueBaseSpawn)

                                        redBase.ownerTeam = TeamColor.RED
                                        blueBase.ownerTeam = TeamColor.BLUE
                                        plugin.teamManager.teamsCapitals[TeamColor.RED] = redBase.location
                                        plugin.teamManager.teamsCapitals[TeamColor.BLUE] = blueBase.location
                                        plugin.structurePlacer.refreshBaseVisual(redBase, isCapital = true)
                                        plugin.structurePlacer.refreshBaseVisual(blueBase, isCapital = true)

                                        for (player in org.bukkit.Bukkit.getOnlinePlayers()) {
                                            player.sendMessage("战场已就绪！正在空降中...")
                                            val spawnLoc = org.bukkit.Location(battleWorld, 0.0, 150.0, 0.0)
                                            val targetLoc = plugin.teamManager.teamsCapitals[plugin.teamManager.getPlayerTeam(player)]
                                                ?.clone()?.add(0.0, 3.0, 0.0) ?: spawnLoc
                                            player.teleport(targetLoc)
                                            player.gameMode = org.bukkit.GameMode.SURVIVAL

                                            // 【新增】游戏开始设置 2 倍血量
                                            player.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 40.0
                                            player.health = 40.0

                                            player.inventory.addItem(org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD, 16))
                                            player.inventory.addItem(org.bukkit.inventory.ItemStack(org.bukkit.Material.OAK_BOAT, 1))
                                            plugin.mapManager.giveMapToPlayer(player)
                                            player.sendMessage("")
                                            player.sendMessage("")
                                            player.sendMessage("§8================ §e§l领地战争 (LandFight) §8================")
                                            player.sendMessage("§7▶ §c核心目标：§f保护己方大本营，占领更多全图据点并消灭敌人！")
                                            player.sendMessage("§7▶ §a占领机制：§f找到中立或敌方据点，§c直接攻击中心的羊§f即可夺取。")
                                            player.sendMessage("§7▶ §b大本营转移：§f空手 §a右键 §f己方据点的羊，可将其设为新的复活点。")
                                            player.sendMessage("§7▶ §6战略控制台：§f在自家据点内按下 §e[潜行 + F] §f打开据点菜单！")
                                            player.sendMessage("§8=====================================================")
                                            player.sendMessage("")
                                            player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
                                        }
                                        isPvPEnabled = true
                                        plugin.currentGameTask = GameTask(plugin)
                                        plugin.currentGameTask?.runTaskTimer(plugin, 0L, 20L)
                                    })


                                } else {
                                    plugin.logger.severe { "未能找到红蓝大本营对象！" }
                                    switchState(GameState.LOBBY)
                                }
                            } catch (e: Exception) {
                                plugin.logger.severe { "分配大本营时发生崩溃！" }
                                e.printStackTrace()
                            }
                        }, 50L)
                    }
                } else {
                    plugin.logger.severe("Could not create battle world!")
                    switchState(GameState.LOBBY)
                }

            }
            GameState.RESET -> {
                isPvPEnabled = false
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

                    // 【新增】回到大厅恢复原版血量
                    player.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 20.0
                    player.health = 20.0

                    player.setDisplayName(player.name)
                    player.setPlayerListName(player.name)

                    for (effect in player.activePotionEffects) {
                        player.removePotionEffect(effect.type)
                    }

                    val advancementIterator = org.bukkit.Bukkit.getServer().advancementIterator()
                    while (advancementIterator.hasNext()) {
                        val advancement = advancementIterator.next()
                        val progress = player.getAdvancementProgress(advancement)
                        for (criteria in progress.awardedCriteria) {
                            progress.revokeCriteria(criteria)
                        }
                    }

                    player.sendMessage("§a§l[系统] §f游戏已结束")
                }

                plugin.structurePlacer.networkGraph.clearGraph()
                plugin.structurePlacer.activeBases.clear()
                plugin.structurePlacer.allBaseStructureBlocks.clear()
                plugin.structurePlacer.allBaseBounds.clear()

                plugin.teamManager.playerTeams.clear()
                plugin.teamManager.teamsCapitals.clear()

                plugin.worldManager.resetBattleWorld()

                plugin.teamManager.clearScoreboardTeams()

                plugin.mapManager.clearMap()
                switchState(GameState.LOBBY)
            }
        }
    }
}