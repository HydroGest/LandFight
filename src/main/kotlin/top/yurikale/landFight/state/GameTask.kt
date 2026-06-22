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
        // 给所有在线玩家加上
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

        // 每 5 秒判定一次据点连通性
        if (timeLeft % 5 == 0) {
            for (base in plugin.structurePlacer.activeBases.values) {
                if (base.ownerTeam == TeamColor.NEUTRAL) continue

                val homeLoc = plugin.teamManager.teamsCapitals[base.ownerTeam]
                // 这里假设你在 networkGraph 里有类似方法判断是否与大本营连通
                val isConnected = homeLoc != null && plugin.structurePlacer.networkGraph.isConnected(base.id, getBaseIdAt(homeLoc))

                if (!isConnected) {
                    // TODO: 逻辑分支
                    // 1. 如果据点断网，可以通过给玩家发消息、或者让据点变色（如变成灰色/闪烁）来提示
                    // 2. 这里可以设置据点产出资源为 0
                }
            }
        }

        // 格式化时分秒
        val minute = timeLeft / 60
        val sec = timeLeft % 60
        val timeText = "距离游戏结束：${minute}分${sec.toString().padStart(2, '0')}秒"
        // 更新BossBar标题与进度
        gameCountdownBar?.setTitle(timeText)
        gameCountdownBar?.progress = timeLeft.toDouble() / totalGameSec.toDouble()

        if (timeLeft % 300 == 0) {
            Bukkit.broadcastMessage("[系统] 距离游戏结束还有 ${minute} 分钟！")
        }

        val redAlive = plugin.teamManager.getAliveCount(TeamColor.RED)
        val blueAlive = plugin.teamManager.getAliveCount(TeamColor.BLUE)
        val hasRedCapital = plugin.teamManager.teamsCapitals.containsKey(TeamColor.RED)
        val hasBlueCapital = plugin.teamManager.teamsCapitals.containsKey(TeamColor.BLUE)

        // 队伍全员离线1分钟判负逻辑不变
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
        plugin.sidebarManager.updateSidebar(redAlive, blueAlive, redBase, blueBase, pointStatus)

        timeLeft--
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

    private fun endGame() {
        this.cancel()
        // 销毁倒计时BossBar
        gameCountdownBar?.removeAll()
        gameCountdownBar = null
        plugin.sidebarManager.removeSidebar()
        plugin.stateManager.switchState(GameState.RESET)
    }
}