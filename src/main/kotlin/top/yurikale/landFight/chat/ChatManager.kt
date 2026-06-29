package top.yurikale.landFight.chat

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.state.GameState
import top.yurikale.landFight.team.TeamColor
import java.util.UUID

class ChatManager(private val plugin: LandFight) {
    enum class ChatChannel {
        TEAM, GLOBAL
    }
    private val playerChannels = mutableMapOf<UUID, ChatChannel>()
    fun getChannel(uuid: UUID): ChatChannel {
        return playerChannels.getOrDefault(uuid, ChatChannel.TEAM)
    }
    fun setChannel(uuid: UUID, channel: ChatChannel) {
        playerChannels[uuid] = channel
    }
    fun resetChannel(uuid: UUID) {
        playerChannels.remove(uuid)
    }
    fun handleChat(player: Player, message: String): Boolean {
        val state = plugin.stateManager.currentState

        if (state == GameState.LOBBY || state == GameState.RESET) {
            return false
        }

        if (state == GameState.IN_GAME) {
            val channel = getChannel(player.uniqueId)
            val team = plugin.teamManager.getPlayerTeam(player)
            val teamPrefix = getTeamPrefix(team)
            when (channel) {
                ChatChannel.TEAM -> {
                    val formatted = "$teamPrefix §f${player.name} §7» §f$message"
                    Bukkit.getOnlinePlayers().forEach { p ->
                        val pTeam = plugin.teamManager.getPlayerTeam(p)
                        if (pTeam == team || p.hasPermission("landfight.chat.spy")) {
                            p.sendMessage(formatted)
                        }
                    }
                    return true
                }
                ChatChannel.GLOBAL -> {
                    val formatted = "§6[全局] $teamPrefix §f${player.name} §7» §f$message"
                    Bukkit.broadcastMessage(formatted)
                    return true
                }
            }
        }
        return false
    }

    fun broadcastGlobal(player: Player, message: String) {
        val team = plugin.teamManager.getPlayerTeam(player)
        val teamPrefix = getTeamPrefix(team)
        Bukkit.broadcastMessage("§6[全局] $teamPrefix §f${player.name} §7» §f$message")
    }

    // 用于 /t 指令直接发送队伍消息
    fun broadcastTeam(player: Player, message: String) {
        val team = plugin.teamManager.getPlayerTeam(player)
        if (team == TeamColor.NEUTRAL) {
            player.sendMessage("§c你还没有加入任何队伍，无法使用队伍聊天！")
            return
        }
        val teamPrefix = getTeamPrefix(team)
        val formatted = "$teamPrefix §f${player.name} §7» §f$message"
        Bukkit.getOnlinePlayers().forEach { p ->
            val pTeam = plugin.teamManager.getPlayerTeam(p)
            if (pTeam == team || p.hasPermission("landfight.chat.spy")) {
                p.sendMessage(formatted)
            }
        }
    }

    private fun getTeamPrefix(team: TeamColor): String {
        return when (team) {
            TeamColor.RED -> "§c[红队]"
            TeamColor.BLUE -> "§9[蓝队]"
            else -> "§7[中立]"
        }
    }
}