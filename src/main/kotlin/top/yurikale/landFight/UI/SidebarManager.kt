package top.yurikale.landFight.ui

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import top.yurikale.landFight.LandFight

class SidebarManager(private val plugin: LandFight) {

    private val scoreboard: Scoreboard = Bukkit.getScoreboardManager()!!.newScoreboard
    private val objective = scoreboard.registerNewObjective("game_info", "dummy", "${ChatColor.GOLD}${ChatColor.BOLD}战场信息")

    init {
        objective.displaySlot = DisplaySlot.SIDEBAR
        val mainSb = Bukkit.getScoreboardManager()!!.mainScoreboard

        val mainRed = mainSb.getTeam("LF_RED")
        if (mainRed != null) {
            val sideRed = scoreboard.getTeam("LF_RED") ?: scoreboard.registerNewTeam("LF_RED")
            sideRed.color = mainRed.color
            sideRed.prefix = mainRed.prefix
            sideRed.setAllowFriendlyFire(mainRed.allowFriendlyFire())
            mainRed.entries.forEach { sideRed.addEntry(it) }
        }

        val mainBlue = mainSb.getTeam("LF_BLUE")
        if (mainBlue != null) {
            val sideBlue = scoreboard.getTeam("LF_BLUE") ?: scoreboard.registerNewTeam("LF_BLUE")
            sideBlue.color = mainBlue.color
            sideBlue.prefix = mainBlue.prefix
            sideBlue.setAllowFriendlyFire(mainBlue.allowFriendlyFire())
            mainBlue.entries.forEach { sideBlue.addEntry(it) }
        }
    }

    fun updateSidebar(redCount: Int, blueCount: Int, redBaseExists: Boolean, blueBaseExists: Boolean, points: String, protectionTime: Int) {
        val mainSb = Bukkit.getScoreboardManager()!!.mainScoreboard
        val mainRed = mainSb.getTeam("LF_RED")
        val mainBlue = mainSb.getTeam("LF_BLUE")

        if (mainRed != null) {
            val sideRed = scoreboard.getTeam("LF_RED") ?: scoreboard.registerNewTeam("LF_RED")
            sideRed.color = mainRed.color
            sideRed.prefix = mainRed.prefix
            sideRed.setAllowFriendlyFire(mainRed.allowFriendlyFire())
            sideRed.entries.forEach { sideRed.removeEntry(it) }
            mainRed.entries.forEach { sideRed.addEntry(it) }
        }
        if (mainBlue != null) {
            val sideBlue = scoreboard.getTeam("LF_BLUE") ?: scoreboard.registerNewTeam("LF_BLUE")
            sideBlue.color = mainBlue.color
            sideBlue.prefix = mainBlue.prefix
            sideBlue.setAllowFriendlyFire(mainBlue.allowFriendlyFire())
            sideBlue.entries.forEach { sideBlue.removeEntry(it) }
            mainBlue.entries.forEach { sideBlue.addEntry(it) }
        }

        scoreboard.entries.forEach { scoreboard.resetScores(it) }

        val lines = mutableListOf<String>()

        // 【新增】如果处于保护期，在最上方显示倒计时
        if (protectionTime > 0) {
            val m = protectionTime / 60
            val s = protectionTime % 60
            lines.add("${ChatColor.YELLOW}基地保护: ${ChatColor.WHITE}${m}分${s.toString().padStart(2, '0')}秒")
            lines.add(" ") // 分隔符
        }

        lines.add("  ")
        lines.add("${ChatColor.RED}红队人数: ${ChatColor.WHITE}$redCount")
        lines.add("${ChatColor.RED}红队基地: ${if (redBaseExists) "§a存活" else "§c已摧毁"}")
        lines.add("   ")
        lines.add("${ChatColor.BLUE}蓝队人数: ${ChatColor.WHITE}$blueCount")
        lines.add("${ChatColor.BLUE}蓝队基地: ${if (blueBaseExists) "§a存活" else "§c已摧毁"}")
        lines.add("    ")
        lines.add("${ChatColor.YELLOW}占领据点: ${ChatColor.WHITE}$points")
        lines.add("  ")
        lines.add("${ChatColor.AQUA}   landmc.fun")

        for (i in lines.indices.reversed()) {
            objective.getScore(lines[i]).score = i
        }
        for (player in Bukkit.getOnlinePlayers()) {
            player.scoreboard = scoreboard
        }
    }

    fun removeSidebar() {
        for (player in Bukkit.getOnlinePlayers()) {
            player.scoreboard = Bukkit.getScoreboardManager()!!.mainScoreboard
        }
        scoreboard.entries.forEach { scoreboard.resetScores(it) }
    }
}