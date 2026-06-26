package top.yurikale.landFight.system

import org.bukkit.Location

object BlockPosUtil {
    fun isSameBlockPos(loc1: Location, loc2: Location): Boolean {
        return loc1.world?.name == loc2.world?.name &&
                loc1.blockX == loc2.blockX &&
                loc1.blockY == loc2.blockY &&
                loc1.blockZ == loc2.blockZ
    }
}