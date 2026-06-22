package top.yurikale.landFight.state

import org.bukkit.Location
import top.yurikale.landFight.team.TeamColor
import java.util.UUID

data class Base(
    val id: Int,
    val location: Location,
    var ownerTeam: TeamColor? = null,
    var level: Int = 1, // 据点等级 1-3
    var sheepEntityId: UUID? = null, // 核心羊的 UUID
    var guards: MutableSet<UUID> = mutableSetOf(), // 存活的守卫 UUID
    // 资源产出权重 (异步扫描后填入)
    var ironWeight: Double = 0.0,
    var woodWeight: Double = 0.0,
    var inAbsoluteProtection: Boolean = false
)