package top.yurikale.landFight.command

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import top.yurikale.landFight.LandFight
import top.yurikale.landFight.chat.ChatManager
import top.yurikale.landFight.state.GameState

class ChatCommand(private val plugin: LandFight) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) return true

        if (plugin.stateManager.currentState != GameState.IN_GAME) {
            sender.sendMessage("§c游戏未开始，当前默认全局聊天。")
            return true
        }

        val msg = args.joinToString(" ")
        val cmdName = command.name.lowercase()

        // 根据指令名判断目标频道
        val targetChannel = when (cmdName) {
            "team", "t" -> ChatManager.ChatChannel.TEAM
            "shout", "hh", "g" -> ChatManager.ChatChannel.GLOBAL
            else -> return false
        }

        // 1. 如果带了参数，直接作为对应频道的消息发送（不改变默认频道状态）
        if (msg.isNotEmpty()) {
            when (targetChannel) {
                ChatManager.ChatChannel.TEAM -> plugin.chatManager.broadcastTeam(sender, msg)
                ChatManager.ChatChannel.GLOBAL -> plugin.chatManager.broadcastGlobal(sender, msg)
            }
            return true
        }

        // 2. 没带参数，则切换玩家的默认聊天频道
        val current = plugin.chatManager.getChannel(sender.uniqueId)
        if (current == targetChannel) {
            sender.sendMessage("§e你已经在 ${if (targetChannel == ChatManager.ChatChannel.TEAM) "§b队伍" else "§6全局"} §e聊天模式了。")
        } else {
            plugin.chatManager.setChannel(sender.uniqueId, targetChannel)
            sender.sendMessage("§a已切换为 ${if (targetChannel == ChatManager.ChatChannel.TEAM) "§b队伍聊天" else "§6全局喊话"} §a模式。")
        }
        return true
    }
}