package top.yurikale.landFight.state

import org.bukkit.Location
import org.bukkit.Material
import top.yurikale.landFight.team.TeamColor
import java.util.UUID

data class Base(
    val id: Int,
    val location: Location,
    var ownerTeam: TeamColor? = null,
    var level: Int = 1,
    var sheepEntityId: UUID? = null,
    var guards: MutableSet<UUID> = mutableSetOf(),
    var inAbsoluteProtection: Boolean = false,
    var healthTimer: Int = 0,

    var isScanned: Boolean = false, // 是否已经完成地形扫描
    // 资源产出权重表 (材质 -> 权重比例，例如 0.05 代表 5%)
    val resourceWeights: MutableMap<Material, Double> = mutableMapOf(),
    // 小数累加器 (材质 -> 积累的零碎产量)
    val resourceAccumulator: MutableMap<Material, Double> = mutableMapOf(),
    // 最终生成的可用库存 (材质 -> 整数数量)
    val resourceStorage: MutableMap<Material, Int> = mutableMapOf(),
    var isLogisticsEnabled: Boolean = false, // 是否开启自动回传
    val resourceSourceMark: MutableMap<String, Int> = mutableMapOf<String, Int>()
)