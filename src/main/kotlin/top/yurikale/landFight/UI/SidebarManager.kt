package top.yurikale.landFight.ui

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import top.yurikale.landFight.LandFight

class SidebarManager(private val plugin: LandFight) {

    // 缓存记分板，避免重复创建
    private val scoreboard: Scoreboard = Bukkit.getScoreboardManager()!!.newScoreboard
    private val objective = scoreboard.registerNewObjective("game_info", "dummy", "${ChatColor.GOLD}${ChatColor.BOLD}战场信息")

    init {
        objective.displaySlot = DisplaySlot.SIDEBAR
        val mainSb = Bukkit.getScoreboardManager()!!.mainScoreboard

        // 同步红队到侧边栏记分板
        val mainRed = mainSb.getTeam("LF_RED")
        if (mainRed != null) {
            val sideRed = scoreboard.getTeam("LF_RED") ?: scoreboard.registerNewTeam("LF_RED")
            sideRed.color = mainRed.color
            sideRed.prefix = mainRed.prefix
            sideRed.setAllowFriendlyFire(mainRed.allowFriendlyFire())
            // 把所有玩家条目复制过来
            mainRed.entries.forEach { sideRed.addEntry(it) }
        }

        // 同步蓝队到侧边栏记分板
        val mainBlue = mainSb.getTeam("LF_BLUE")
        if (mainBlue != null) {
            val sideBlue = scoreboard.getTeam("LF_BLUE") ?: scoreboard.registerNewTeam("LF_BLUE")
            sideBlue.color = mainBlue.color
            sideBlue.prefix = mainBlue.prefix
            sideBlue.setAllowFriendlyFire(mainBlue.allowFriendlyFire())
            mainBlue.entries.forEach { sideBlue.addEntry(it) }
        }
    }

    fun updateSidebar(redCount: Int, blueCount: Int, redBaseExists: Boolean, blueBaseExists: Boolean, points: String) {
        // 每次刷新记分板都同步一次队伍配置与玩家
        val mainSb = Bukkit.getScoreboardManager()!!.mainScoreboard
        val mainRed = mainSb.getTeam("LF_RED")
        val mainBlue = mainSb.getTeam("LF_BLUE")

        // 同步红队
        if (mainRed != null) {
            val sideRed = scoreboard.getTeam("LF_RED") ?: scoreboard.registerNewTeam("LF_RED")
            sideRed.color = mainRed.color
            sideRed.prefix = mainRed.prefix
            sideRed.setAllowFriendlyFire(mainRed.allowFriendlyFire())
            // 清空再重新同步所有玩家，防止不同步
            sideRed.entries.forEach { sideRed.removeEntry(it) }
            mainRed.entries.forEach { sideRed.addEntry(it) }
        }
        // 同步蓝队
        if (mainBlue != null) {
            val sideBlue = scoreboard.getTeam("LF_BLUE") ?: scoreboard.registerNewTeam("LF_BLUE")
            sideBlue.color = mainBlue.color
            sideBlue.prefix = mainBlue.prefix
            sideBlue.setAllowFriendlyFire(mainBlue.allowFriendlyFire())
            sideBlue.entries.forEach { sideBlue.removeEntry(it) }
            mainBlue.entries.forEach { sideBlue.addEntry(it) }
        }

        // 原有清空分数、渲染行逻辑不动
        scoreboard.entries.forEach { scoreboard.resetScores(it) }
        val lines = listOf(
            " ",
            "${ChatColor.RED}红队人数: ${ChatColor.WHITE}$redCount",
            "${ChatColor.RED}红队基地: ${if (redBaseExists) "§a存活" else "§c已摧毁"}",
            "  ",
            "${ChatColor.BLUE}蓝队人数: ${ChatColor.WHITE}$blueCount",
            "${ChatColor.BLUE}蓝队基地: ${if (blueBaseExists) "§a存活" else "§c已摧毁"}",
            "   ",
            "${ChatColor.YELLOW}占领据点: ${ChatColor.WHITE}$points"
        )
        for (i in lines.indices.reversed()) {
            objective.getScore(lines[i]).score = i
        }
        for (player in Bukkit.getOnlinePlayers()) {
            player.scoreboard = scoreboard
        }
    }

    fun removeSidebar() {
        // 1. 把所有玩家切回服务器原生主记分板
        for (player in Bukkit.getOnlinePlayers()) {
            player.scoreboard = Bukkit.getScoreboardManager()!!.mainScoreboard
        }
        // 2. 清空本局所有记分板缓存条目，防止下次刷新带出旧0数据
        scoreboard.entries.forEach { scoreboard.resetScores(it) }
    }
}