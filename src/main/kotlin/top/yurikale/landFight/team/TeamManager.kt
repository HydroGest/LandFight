package top.yurikale.landFight.team

import io.papermc.paper.command.brigadier.argument.ArgumentTypes.player
import top.yurikale.landFight.LandFight
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player

enum class TeamColor(val displayName: String, val colorCode: String) {
    RED("红队", "§c"),
    BLUE("蓝队", "§9"),
    NEUTRAL("中立", "§7")
}

class TeamManager(private val plugin: LandFight) {
    val playerTeams = HashMap<UUID, TeamColor>()
    val teamsCapitals = HashMap<TeamColor, org.bukkit.Location>()

    fun getAliveCount(color: top.yurikale.landFight.team.TeamColor):Int {
        val onlinePlayers = Bukkit.getOnlinePlayers()
        var redAlive = 0
        var blueAlive = 0

        for (player in onlinePlayers) {
            if (player.gameMode != GameMode.SPECTATOR) {
                when (plugin.teamManager.getPlayerTeam(player)) {
                    TeamColor.RED -> redAlive++
                    TeamColor.BLUE -> blueAlive++
                    else -> {}
                }
            }
        }
        return when (color) {
            TeamColor.RED -> redAlive
            TeamColor.BLUE -> blueAlive
            else -> 0
        }
    }

    fun setupPlayerTeam(player: Player, teamColor: TeamColor) {
        playerTeams[player.uniqueId] = teamColor

        // 设置名字颜色（永久生效）
        player.setDisplayName("${teamColor.colorCode}${when (teamColor) { TeamColor.RED -> "§c[红队]"; TeamColor.BLUE -> "§9[蓝队]" else -> {}}} ${player.name}§r")
        player.setPlayerListName("${teamColor.colorCode}${player.name}§r")

        // 加入计分板队伍（头顶颜色 + 队友伤害）
        val scoreboard = Bukkit.getScoreboardManager()?.mainScoreboard ?: return
        if (teamColor == TeamColor.RED) {
            scoreboard.getTeam("LF_RED")?.addEntry(player.name)
        } else if (teamColor == TeamColor.BLUE) {
            scoreboard.getTeam("LF_BLUE")?.addEntry(player.name)
        }
    }

    fun setupScoreboardTeams() {
        val scoreboard = org.bukkit.Bukkit.getScoreboardManager()?.mainScoreboard ?: return

        // 创建或获取红队
        val redTeam = scoreboard.getTeam("LF_RED") ?: scoreboard.registerNewTeam("LF_RED")
        redTeam.color = org.bukkit.ChatColor.RED // 核心：设置头顶名字颜色为红色
        redTeam.setAllowFriendlyFire(false)      // 附赠功能：禁止红队内讧（队友免伤）
        redTeam.prefix = "§c[红队] "             // Tab 列表前缀

        // 创建或获取蓝队
        val blueTeam = scoreboard.getTeam("LF_BLUE") ?: scoreboard.registerNewTeam("LF_BLUE")
        blueTeam.color = org.bukkit.ChatColor.BLUE // 核心：设置头顶名字颜色为蓝色
        blueTeam.setAllowFriendlyFire(false)       // 禁止蓝队内讧
        blueTeam.prefix = "§9[蓝队] "              // Tab 列表前缀
    }

    fun assignTeam() {
        playerTeams.clear()
        val players = Bukkit.getOnlinePlayers().shuffled()
        teamsCapitals.clear()
        setupScoreboardTeams() // 提前创建队伍

        for ((index, player) in players.withIndex()) {
            val team = if (index % 2 == 0) TeamColor.RED else TeamColor.BLUE
            // 使用统一方法设置颜色
            setupPlayerTeam(player, team)
            player.sendMessage("§a游戏开始！你被分配到了 ${team.colorCode}${team.displayName}§a！")
        }
    }

    fun getPlayerTeam(player: Player): TeamColor {
        return playerTeams[player.uniqueId] ?: TeamColor.NEUTRAL
    }

    fun clearScoreboardTeams() {
        val scoreboard = org.bukkit.Bukkit.getScoreboardManager()?.mainScoreboard ?: return
        scoreboard.getTeam("LF_RED")?.unregister()
        scoreboard.getTeam("LF_BLUE")?.unregister()
    }
}