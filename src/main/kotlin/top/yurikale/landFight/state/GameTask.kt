package top.yurikale.landFight.state

import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.scheduler.BukkitRunnable
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.team.TeamColor
import org.bukkit.Location

class GameTask(private val plugin: LandFight) : BukkitRunnable() {
    private var timeLeft = 2700 // 总45分钟 = 2700秒

    private val totalGameSec = 2700

    private var redEmptyTick = 0
    private var blueEmptyTick = 0

    private var gameCountdownBar: BossBar? = null

    private var protectionTimeLeft = 900

    private fun getBaseIdAt(loc: Location): Int {
        return plugin.structurePlacer.activeBases.values.find {
            it.location.blockX == loc.blockX && it.location.blockY == loc.blockY && it.location.blockZ == loc.blockZ
        }?.id ?: -1
    }

    init {
        // 游戏启动时创建BossBar
        gameCountdownBar = Bukkit.createBossBar(
            "距离游戏结束：45分00秒",
            BarColor.YELLOW,
            BarStyle.SEGMENTED_10
        )
        Bukkit.getOnlinePlayers().forEach { gameCountdownBar?.addPlayer(it) }
    }

    override fun run() {
        // 实时同步给新加入玩家
        gameCountdownBar?.let { bar ->
            Bukkit.getOnlinePlayers().forEach { p ->
                if (!bar.players.contains(p)) bar.addPlayer(p)
            }
        }

        if (timeLeft <= 0) {
            checkWinCondition(timeout = true)
            return
        }

        // ================ 大本营保护期倒计时与检测 ================
        var protectionDisplay = -1
        if (protectionTimeLeft > 0) {
            protectionDisplay = protectionTimeLeft
            protectionTimeLeft--
            if (protectionTimeLeft == 0) {
                Bukkit.broadcastMessage("§e【系统】§f15分钟大本营保护期已结束，现在可以进攻敌方大本营了！")
            }
        }


        if (timeLeft % 10 == 0) {
            plugin.industryManager.tickIndustry()
        }

        // 每 5 秒判定一次据点连通性
        if (timeLeft % 5 == 0) {
            for (base in plugin.structurePlacer.activeBases.values) {
                if (base.ownerTeam == TeamColor.NEUTRAL) continue

                val homeLoc = plugin.teamManager.teamsCapitals[base.ownerTeam]
                val isConnected = homeLoc != null && plugin.structurePlacer.networkGraph.isConnected(base.id, getBaseIdAt(homeLoc))

                if (!isConnected) {
                    // TODO: 逻辑分支
                    // 1. 如果据点断网，可以通过给玩家发消息、或者让据点变色（如变成灰色/闪烁）来提示
                    // 2. 这里可以设置据点产出资源为 0
                }
            }
        }

        // ================ 每秒刷新所有打开工业菜单的玩家UI ================
        // 作用：1. 让10秒进度条平滑滚动  2. 多玩家操作实时同步，防止显示不同步
        refreshIndustryMenus()

        // 格式化时分秒
        val minute = timeLeft / 60
        val sec = timeLeft % 60
        val timeText = "距离游戏结束：${minute}分${sec.toString().padStart(2, '0')}秒"
        gameCountdownBar?.setTitle(timeText)
        gameCountdownBar?.progress = timeLeft.toDouble() / totalGameSec.toDouble()

        if (timeLeft % 300 == 0) {
            Bukkit.broadcastMessage("[系统] 距离游戏结束还有 ${minute} 分钟！")
        }

        val redAlive = plugin.teamManager.getAliveCount(TeamColor.RED)
        val blueAlive = plugin.teamManager.getAliveCount(TeamColor.BLUE)
        val hasRedCapital = plugin.teamManager.teamsCapitals.containsKey(TeamColor.RED)
        val hasBlueCapital = plugin.teamManager.teamsCapitals.containsKey(TeamColor.BLUE)

        // 队伍全员离线1分钟判负逻辑
        if (hasRedCapital && redAlive == 0) {
            redEmptyTick++
            if (redEmptyTick == 10 || redEmptyTick == 30 || redEmptyTick == 50) {
                Bukkit.broadcastMessage("§c【警告】红队所有玩家离线，再等待${60 - redEmptyTick}秒将直接判负！")
            }
            if (redEmptyTick >= 60) {
                Bukkit.broadcastMessage("§c红队全员离线超过1分钟，游戏结束，§9蓝队§c胜利！")
                endGame()
                return
            }
        } else {
            redEmptyTick = 0
        }

        if (hasBlueCapital && blueAlive == 0) {
            blueEmptyTick++
            if (blueEmptyTick == 10 || blueEmptyTick == 30 || blueEmptyTick == 50) {
                Bukkit.broadcastMessage("§9【警告】蓝队所有玩家离线，再等待${60 - blueEmptyTick}秒将直接判负！")
            }
            if (blueEmptyTick >= 60) {
                Bukkit.broadcastMessage("§9蓝队全员离线超过1分钟，游戏结束，§c红队§9胜利！")
                endGame()
                return
            }
        } else {
            blueEmptyTick = 0
        }

        checkWinCondition(timeout = false)

        val redBase = hasRedCapital
        val blueBase = hasBlueCapital
        val pointStatus = "${TeamColor.RED.colorCode}${plugin.structurePlacer.getCapturedNumber(TeamColor.RED)} §a- ${TeamColor.BLUE.colorCode}${plugin.structurePlacer.getCapturedNumber(TeamColor.BLUE)}"

        plugin.sidebarManager.updateSidebar(redAlive, blueAlive, redBase, blueBase, pointStatus, protectionDisplay)

        // 遍历在线生存模式玩家，发送据点提示 & 保护期惩罚
        for (player in Bukkit.getOnlinePlayers()) {
            if (player.gameMode != org.bukkit.GameMode.SURVIVAL) continue

            // 大本营保护期入侵检测
            if (protectionDisplay > 0) {
                val myTeam = plugin.teamManager.getPlayerTeam(player)
                if (myTeam == TeamColor.RED || myTeam == TeamColor.BLUE) {
                    val enemyTeam = if (myTeam == TeamColor.RED) TeamColor.BLUE else TeamColor.RED
                    val enemyCapital = plugin.teamManager.teamsCapitals[enemyTeam]

                    if (enemyCapital != null && enemyCapital.world?.name == player.world?.name) {
                        val dx = player.location.x - enemyCapital.x
                        val dz = player.location.z - enemyCapital.z
                        // 2D距离判断，半径 128
                        if (dx * dx + dz * dz <= 128 * 128) {
                            // 给予虚弱 II 和反胃 II，持续3秒（60 ticks），覆盖粒子隐藏
                            player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.WEAKNESS, 60, 1, false, false, true))
                            player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.NAUSEA, 60, 1, false, false, true))

                            player.spigot().sendMessage(
                                net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                                net.md_5.bungee.api.chat.TextComponent("§c⚠ 处于敌方大本营保护区(半径128)内！")
                            )
                            continue // 跳过下方普通的据点提示
                        }
                    }
                }
            }

            val currentBase = plugin.structurePlacer.getBaseAtPlayer(player.location)
            if (currentBase != null) {
                val team = currentBase.ownerTeam ?: TeamColor.NEUTRAL
                val teamPrefix = "${team.colorCode}[${team.displayName}据点] "

                player.spigot().sendMessage(
                    net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    net.md_5.bungee.api.chat.TextComponent("${teamPrefix}§f已进入据点。§e左键攻击羊占领，右键羊设为大本营 §b| §6[潜行+F] 打开据点菜单")
                )
            }
        }


        plugin.structurePlacer.activeBases.values.forEach { base ->
            val sheep = plugin.server.getEntity(base.sheepEntityId ?: return@forEach) as? org.bukkit.entity.Sheep ?: return@forEach
            val maxHp = sheep.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 50.0

            if (sheep.health < maxHp) {
                val shouldHeal = when (base.level) {
                    1 -> timeLeft % 3 == 0
                    2 -> timeLeft % 2 == 0
                    3 -> timeLeft % 1 == 0
                    else -> false
                }

                if (shouldHeal) {
                    sheep.health = (sheep.health + 1.0).coerceAtMost(maxHp)
                    plugin.structurePlacer.refreshBaseVisual(base, plugin.structurePlacer.isBaseCapital(base.id))
                }
            }
        }

        timeLeft--
    }

    /**
     * 每秒刷新所有打开工业菜单的玩家的UI
     * 1. 让10秒进度条平滑滚动（每秒更新一次位置）
     * 2. 多玩家操作实时同步（A玩家提取物资，B玩家界面立刻刷新）
     */
    private fun refreshIndustryMenus() {
        for (player in Bukkit.getOnlinePlayers()) {
            val topInv = player.openInventory.topInventory
            val holder = topInv.holder
            if (holder is top.yurikale.landFight.ui.IndustryMenuHolder) {
                holder.setupMenu()
            } else if (holder is top.yurikale.landFight.ui.GuardMenuHolder) {
                holder.setupMenu()
            }
        }
    }

    private fun checkWinCondition(timeout: Boolean) {
        val redAlive = plugin.teamManager.getAliveCount(TeamColor.RED)
        val blueAlive = plugin.teamManager.getAliveCount(TeamColor.BLUE)
        if (timeout) {
            Bukkit.broadcastMessage("时间到！正在结算据点数量...")
            calculateBasesAndEnd();
            return
        }

        val hasRedHome = plugin.teamManager.teamsCapitals.containsKey(TeamColor.RED)
        val hasBlueHome = plugin.teamManager.teamsCapitals.containsKey(TeamColor.BLUE)

        if (!hasRedHome && redAlive == 0 && blueAlive > 0) {
            Bukkit.broadcastMessage("红队已全军覆没！")
            endGame()
        } else if (!hasBlueHome && blueAlive == 0 && redAlive > 0) {
            Bukkit.broadcastMessage("蓝队已全军覆没！")
            endGame()
        } else if (!hasBlueHome && !hasRedHome && redAlive == 0 && blueAlive == 0) {
            Bukkit.broadcastMessage("平局！")
            endGame()
        }
    }

    private fun calculateBasesAndEnd() {
        var redBases = 0
        var blueBases = 0

        for (base in plugin.structurePlacer.activeBases.values) {
            when (base.ownerTeam) {
                TeamColor.RED -> redBases++
                TeamColor.BLUE -> blueBases++
                else -> {}
            }
        }

        Bukkit.broadcastMessage("最终据点数：红队（${redBases}）-蓝队（${blueBases}）")
        when {
            redBases > blueBases -> Bukkit.broadcastMessage("§c红队§f占领了更多据点，红队胜利！")
            blueBases > redBases -> Bukkit.broadcastMessage("§9蓝队§f占领了更多据点，蓝队胜利！")
            else -> Bukkit.broadcastMessage("平局！")
        }
        endGame()
    }

    fun endGame() {
        this.cancel()
        gameCountdownBar?.removeAll()
        gameCountdownBar = null
        plugin.sidebarManager.removeSidebar()
        plugin.stateManager.switchState(GameState.RESET)
    }
}